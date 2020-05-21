import * as untypedSymbolData from './insert_symbol-data.json';

export interface SymbolCategory {
  name: string;
  codepointFirst: number;
  codepointLast: number;
}

export interface SymbolCharacter {
  name: string;
  value: string;
  codepoint: number;
}

export const CATEGORY_ALL = {name: "All", codepointFirst: 0, codepointLast: Number.MAX_VALUE};

class SymbolDataManager {
  private symbolData: Array<SymbolCharacter> = untypedSymbolData.symbols;
  private blockData: Array<SymbolCategory> = untypedSymbolData.blocks;

  public getCategories(): Array<SymbolCategory> {
    return [CATEGORY_ALL, ...this.blockData];
    
  }

  // TODO: move categories into a separate object with start and end index for getting items
  // TODO: Keep codepoint offsets into array so we can return a subset of array?
  public getSymbols(symbolCategory: SymbolCategory) {
    if (symbolCategory.name === CATEGORY_ALL.name) {
      return this.symbolData;
    }
    return this.symbolData.filter((symbol) => {
      return symbol.codepoint >= symbolCategory.codepointFirst && symbol.codepoint <= symbolCategory.codepointLast;
    });
  }
}

export default SymbolDataManager;