'use strict';
const fs = require('node:fs/promises');
const path = require('node:path');
const { build, Platform } = require('electron-builder');
const { Arch } = require('builder-util');

// electron-builder 26.15.3 already extracts the NSIS uninstaller without Wine
// on macOS. Use that same validated PE/NSIS reader on Linux. This only extracts
// the compiler-produced uninstaller; it does not simulate a Windows install.
// Based on computeScriptAndSignUninstaller in electron-builder (MIT license).
if (process.platform === 'linux') {
  const { NsisTarget } = require('app-builder-lib/out/targets/nsis/NsisTarget');
  const { UninstallerReader, nsisTemplatesDir } = require('app-builder-lib/out/targets/nsis/nsisUtil');
  const original = NsisTarget.prototype.computeScriptAndSignUninstaller;
  NsisTarget.prototype.computeScriptAndSignUninstaller = async function(defines, commands, installerPath, sharedHeader, archs) {
    const custom = await this.packager.getResource(this.options.script, 'installer.nsi');
    if (custom) return original.call(this, defines, commands, installerPath, sharedHeader, archs);
    const script = await fs.readFile(path.join(nsisTemplatesDir, 'installer.nsi'), 'utf8');
    const uninstallerPath = path.join(this.outDir, `${path.basename(installerPath, 'exe')}__uninstaller.exe`);
    defines.BUILD_UNINSTALLER = null;
    defines.UNINSTALLER_OUT_FILE = path.win32.join('Z:', uninstallerPath);
    await this.executeMakensis(defines, commands, sharedHeader + await this.computeFinalScript(script, false, archs));
    await UninstallerReader.exec(installerPath, uninstallerPath);
    await this.packager.signIf(uninstallerPath);
    delete defines.BUILD_UNINSTALLER;
    defines.UNINSTALLER_OUT_FILE = uninstallerPath;
    console.log('NSIS: compiler-produced uninstaller extracted and PE/NSIS signatures validated by electron-builder.');
    return { script, isCustomScript: false };
  };
}
const cache = process.env.electron_config_cache;
build({ publish: 'never', targets: Platform.WINDOWS.createTarget('nsis', Arch.x64), ...(cache ? { config: { electronDownload: { cache } } } : {}) })
  .catch(error => { console.error(error.message); process.exitCode = 1; });
