/*
 * session_launcher.js
 *
 * Copyright (C) 2021 by RStudio, PBC
 *
 * Unless you have received this program directly from RStudio pursuant
 * to the terms of a commercial license agreement with RStudio, then
 * this program is licensed to you under the terms of version 3 of the
 * GNU Affero General Public License. This program is distributed WITHOUT
 * ANY EXPRESS OR IMPLIED WARRANTY, INCLUDING THOSE OF NON-INFRINGEMENT,
 * MERCHANTABILITY OR FITNESS FOR A PARTICULAR PURPOSE. Please refer to the
 * AGPL (http://www.gnu.org/licenses/agpl-3.0.txt) for more details.
 *
 */

const child_process = require('child_process');
const fs = require('fs');
const path = require('path');

let s_launcherToken = "";

module.exports = class SessionLauncher {
  constructor(sessionPath, confPath) {
      this.sessionPath_ = sessionPath;
      this.confPath_ = confPath;
  }

  launchSession(argList) {
    // #ifdef Q_OS_DARWIN
    // on macOS with the hardened runtime, we can no longer rely on dyld
    // to lazy-load symbols from libR.dylib; to resolve this, we use
    // DYLD_INSERT_LIBRARIES to inject the library we wish to use on
    // launch 
    let rHome = process.env.R_HOME;
    let rLib = `${rHome}/lib/libR.dylib`;
    if (fs.existsSync(rLib))
    {
        process.env.DYLD_INSERT_LIBRARIES = path.resolve(rLib);
    }
    // #endif // Q_OS_DARWIN

    const session = child_process.spawn(this.sessionPath_, argList);
    session.stdout.on('data', (data) => {
      console.log(`stdout: ${data}`)
    });
    session.stderr.on('data', (data) => {
      console.log(`stderr: ${data}`);
    });
    session.on('close', (code) => {
      console.log(`child process exited with code ${code}`);
    });
  }

  launchFirstSession(installPath, devMode) {
    let launchContext = this.buildLaunchContext();

    // show help home on first run
    launchContext.argList.push("--show-help-home", "1");

    this.launchSession(launchContext.argList);
  }

  get launcherToken() {
    if (s_launcherToken.length == 0) {
       s_launcherToken = "7F83A8BD";
    }
    return s_launcherToken;
  }

  buildLaunchContext() {
    return {
        host: "127.0.0.1",
        port: 8787,
        url: "http://127.0.0.1:8787",
        argList: [
            "--config-file", "none",
            "--program-mode", "desktop",
            "--www-port", "8787",
            "--launcher-token", this.launcherToken,
        ],
    }
  }
}