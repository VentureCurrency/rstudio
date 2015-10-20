/*
 * Copyright 2008 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package com.google.gwt.dev.jjs.impl;

import com.google.gwt.dev.jjs.SourceInfo;
import com.google.gwt.dev.jjs.ast.Context;
import com.google.gwt.dev.jjs.ast.JArrayRef;
import com.google.gwt.dev.jjs.ast.JArrayType;
import com.google.gwt.dev.jjs.ast.JBinaryOperation;
import com.google.gwt.dev.jjs.ast.JBinaryOperator;
import com.google.gwt.dev.jjs.ast.JCastMap;
import com.google.gwt.dev.jjs.ast.JExpression;
import com.google.gwt.dev.jjs.ast.JIntLiteral;
import com.google.gwt.dev.jjs.ast.JLiteral;
import com.google.gwt.dev.jjs.ast.JMethod;
import com.google.gwt.dev.jjs.ast.JMethodCall;
import com.google.gwt.dev.jjs.ast.JModVisitor;
import com.google.gwt.dev.jjs.ast.JNewArray;
import com.google.gwt.dev.jjs.ast.JProgram;
import com.google.gwt.dev.jjs.ast.JReferenceType;
import com.google.gwt.dev.jjs.ast.JRuntimeTypeReference;
import com.google.gwt.dev.jjs.ast.JType;
import com.google.gwt.dev.jjs.ast.RuntimeConstants;
import com.google.gwt.dev.jjs.ast.js.JsonArray;

import java.util.Collections;

/**
 * Replace array accesses and instantiations with calls to the Array class.
 * Depends on {@link CompoundAssignmentNormalizer} and {@link ImplementCastsAndTypeChecks}
 * having already run.
 */
public class ArrayNormalizer {

  private class ArrayVisitor extends JModVisitor {

    @Override
    public void endVisit(JBinaryOperation x, Context ctx) {
      if (x.getOp() != JBinaryOperator.ASG || !(x.getLhs() instanceof JArrayRef)) {
        return;
      }
      JArrayRef arrayRef = (JArrayRef) x.getLhs();
      JType elementType = arrayRef.getType();
      JExpression arrayInstance = arrayRef.getInstance();
      if (elementType.isNullType()) {
        // JNullType will generate a null pointer exception instead,
        return;
      } else if (!(elementType instanceof JReferenceType)) {
        // Primitive array types are statically correct, no need to set check.
        return;
      } else if (!arrayInstance.getType().canBeSubclass() &&
          program.typeOracle.castSucceedsTrivially((JReferenceType) x.getRhs().getType(),
              (JReferenceType) elementType)) {
        // There is no need to check as the static check already proved the cast is correct.
        return;
      }

      // replace this assignment with a call to setCheck()
      JMethodCall call = new JMethodCall(x.getSourceInfo(), null, setCheckMethod);
      call.addArgs(arrayInstance, arrayRef.getIndexExpr(), x.getRhs());
      ctx.replaceMe(call);
    }

    @Override
    public void endVisit(JNewArray x, Context ctx) {
      JArrayType type = x.getArrayType();

      if (x.initializers != null) {
        processInitializers(x, ctx, type);
      } else {
        int realDims = x.dims.size();
        assert (realDims >= 1);
        if (realDims == 1) {
          processDim(x, ctx, type);
        } else {
          processDims(x, ctx, type);
        }
      }
    }

    private JRuntimeTypeReference getElementRuntimeTypeReference(SourceInfo sourceInfo,
        JArrayType arrayType) {
      JType elementType = arrayType.getElementType();
      if (!(elementType instanceof JReferenceType)) {
        // elementType is a primitive type, store check will be performed statically.
        elementType = JReferenceType.NULL_TYPE;
      }

      if (program.typeOracle.isEffectivelyJavaScriptObject(elementType)) {
        /*
         * treat types that are effectively JSO's as JSO's, for the purpose of
         * castability checking
         */
        elementType = program.getJavaScriptObject();
      } else {
        elementType = elementType.getUnderlyingType();
      }

      elementType = program.normalizeJsoType(elementType);
      return new JRuntimeTypeReference(sourceInfo, program.getTypeJavaLangObject(),
          (JReferenceType) elementType);
    }

    private JExpression getOrCreateCastMap(SourceInfo sourceInfo, JArrayType arrayType) {
      JCastMap castableTypeMap = program.getCastMap(arrayType);
      if (castableTypeMap == null) {
        return new JCastMap(sourceInfo, program.getTypeJavaLangObject(),
            Collections.<JReferenceType>emptyList());
      }
      return castableTypeMap;
    }

    private void processDim(JNewArray x, Context ctx, JArrayType arrayType) {
      // override the type of the called method with the array's type
      SourceInfo sourceInfo = x.getSourceInfo();
      JMethodCall call = new JMethodCall(sourceInfo, null, initDim, arrayType);
      JLiteral classLit = x.getLeafTypeClassLiteral();
      JExpression castableTypeMap = getOrCreateCastMap(sourceInfo, arrayType);
      JRuntimeTypeReference arrayElementRuntimeTypeReference =
          getElementRuntimeTypeReference(sourceInfo, arrayType);
      JType elementType = arrayType.getElementType();
      JIntLiteral elementTypeCategory = getTypeCategoryLiteral(elementType);
      JExpression dim = x.dims.get(0);
      call.addArgs(classLit, castableTypeMap, arrayElementRuntimeTypeReference, dim,
          elementTypeCategory, program.getLiteralInt(arrayType.getDims()));
      ctx.replaceMe(call);
    }

    private void processDims(JNewArray x, Context ctx, JArrayType arrayType) {
      // override the type of the called method with the array's type
      SourceInfo sourceInfo = x.getSourceInfo();
      JMethodCall call = new JMethodCall(sourceInfo, null, initDims, arrayType);
      JsonArray castableTypeMaps = new JsonArray(sourceInfo, program.getJavaScriptObject());
      JsonArray elementTypeReferences = new JsonArray(sourceInfo, program.getJavaScriptObject());
      JsonArray dimList = new JsonArray(sourceInfo, program.getJavaScriptObject());
      JType currentElementType = arrayType;
      JLiteral classLit = x.getLeafTypeClassLiteral();
      for (int i = 0; i < x.dims.size(); ++i) {
        // Walk down each type from most dims to least.
        JArrayType curArrayType = (JArrayType) currentElementType;

        JExpression castableTypeMap = getOrCreateCastMap(sourceInfo, curArrayType);
        castableTypeMaps.getExprs().add(castableTypeMap);

        JRuntimeTypeReference elementTypeIdLit = getElementRuntimeTypeReference(sourceInfo,
            curArrayType);
        elementTypeReferences.getExprs().add(elementTypeIdLit);

        dimList.getExprs().add(x.dims.get(i));
        currentElementType = curArrayType.getElementType();
      }
      JType leafElementType = currentElementType;
      JIntLiteral leafElementTypeCategory = getTypeCategoryLiteral(leafElementType);
      call.addArgs(classLit, castableTypeMaps, elementTypeReferences, leafElementTypeCategory,
          dimList, program.getLiteralInt(x.dims.size()));
      ctx.replaceMe(call);
    }

    private void processInitializers(JNewArray x, Context ctx, JArrayType arrayType) {
      // override the type of the called method with the array's type
      SourceInfo sourceInfo = x.getSourceInfo();
      JMethodCall call = new JMethodCall(sourceInfo, null, initValues, arrayType);
      JExpression classLitExpression = program.createArrayClassLiteralExpression(x.getSourceInfo(),
          x.getLeafTypeClassLiteral(), arrayType.getDims());
      JExpression castableTypeMap = getOrCreateCastMap(sourceInfo, arrayType);
      JRuntimeTypeReference elementTypeIds = getElementRuntimeTypeReference(sourceInfo, arrayType);
      JsonArray initList = new JsonArray(sourceInfo, program.getJavaScriptObject());
      JIntLiteral leafElementTypeCategory = getTypeCategoryLiteral(arrayType.getElementType());
      for (int i = 0; i < x.initializers.size(); ++i) {
        initList.getExprs().add(x.initializers.get(i));
      }
      call.addArgs(classLitExpression, castableTypeMap, elementTypeIds, leafElementTypeCategory,
          initList);
      ctx.replaceMe(call);
    }

    /**
     * Returns a literal that represent the type category for a type.
     */
    private JIntLiteral getTypeCategoryLiteral(JType type) {
      return JIntLiteral.get(TypeCategory.typeCategoryForType(type, program).ordinal());
    }
  }

  public static void exec(JProgram program) {
    new ArrayNormalizer(program).execImpl();
  }

  private final JMethod initDim;
  private final JMethod initDims;
  private final JMethod initValues;
  private final JProgram program;
  private final JMethod setCheckMethod;

  private ArrayNormalizer(JProgram program) {
    this.program = program;
    setCheckMethod = program.getIndexedMethod(RuntimeConstants.ARRAY_SET_CHECK);
    initDim = program.getIndexedMethod(RuntimeConstants.ARRAY_INIT_DIM);
    initDims = program.getIndexedMethod(RuntimeConstants.ARRAY_INIT_DIMS);
    initValues = program.getIndexedMethod(RuntimeConstants.ARRAY_INIT_VALUES);
  }

  private void execImpl() {
    ArrayVisitor visitor = new ArrayVisitor();
    visitor.accept(program);
  }
}
