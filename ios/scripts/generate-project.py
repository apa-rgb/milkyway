#!/usr/bin/env python3
"""Dependency-free, deterministic Xcode project generator. Sources stay in their original directories."""
import hashlib
import pathlib
import xml.etree.ElementTree as ET

root = pathlib.Path(__file__).resolve().parents[1]
project = root / "Milkyway.xcodeproj"
project.mkdir(exist_ok=True)
objects = {}
def ident(name):
    return hashlib.sha256(name.encode()).hexdigest()[:24].upper()
def obj(name, content):
    key = ident(name)
    objects[key] = content
    return key
def array(items):
    return "(" + ", ".join(items) + ("," if items else "") + ")"
sources = sorted(p.relative_to(root).as_posix() for p in (root / "Sources").rglob("*.swift"))
resources = ["Resources/Assets.xcassets", "Resources/tanks.json", "Resources/MilkywayCloud.plist", "Resources/PrivacyInfo.xcprivacy"]
refs, builds = {}, {}
for path in sources + resources:
    typ = "sourcecode.swift" if path.endswith(".swift") else ("folder.assetcatalog" if path.endswith(".xcassets") else "text.plist.xml" if path.endswith((".plist", ".xcprivacy")) else "text.json")
    refs[path] = obj("ref:" + path, f'isa = PBXFileReference; lastKnownFileType = {typ}; path = "{path}"; sourceTree = SOURCE_ROOT;')
    builds[path] = obj("build:" + path, f'isa = PBXBuildFile; fileRef = {refs[path]};')
product = obj("product", 'isa = PBXFileReference; explicitFileType = wrapper.application; path = Milkyway.app; sourceTree = BUILT_PRODUCTS_DIR;')
products = obj("products", f'isa = PBXGroup; children = {array([product])}; name = Products; sourceTree = "<group>";')
group = obj("main-group", f'isa = PBXGroup; children = {array(list(refs.values()) + [products])}; sourceTree = "<group>";')
source_phase = obj("sources", f'isa = PBXSourcesBuildPhase; buildActionMask = 2147483647; files = {array([builds[p] for p in sources])}; runOnlyForDeploymentPostprocessing = 0;')
resource_phase = obj("resources", f'isa = PBXResourcesBuildPhase; buildActionMask = 2147483647; files = {array([builds[p] for p in resources])}; runOnlyForDeploymentPostprocessing = 0;')
framework_phase = obj("frameworks", 'isa = PBXFrameworksBuildPhase; buildActionMask = 2147483647; files = (); runOnlyForDeploymentPostprocessing = 0;')
project_configs, target_configs = [], []
for configuration in ["Debug", "Release"]:
    debug = configuration == "Debug"
    project_configs.append(obj("project:" + configuration, f'isa = XCBuildConfiguration; name = {configuration}; buildSettings = {{ CLANG_ENABLE_MODULES = YES; SDKROOT = iphoneos; IPHONEOS_DEPLOYMENT_TARGET = 17.0; SWIFT_VERSION = 5.0; }};'))
    settings = 'ASSETCATALOG_COMPILER_APPICON_NAME = AppIcon; CODE_SIGN_STYLE = Automatic; CURRENT_PROJECT_VERSION = 1; GENERATE_INFOPLIST_FILE = NO; INFOPLIST_FILE = Resources/Info.plist; MARKETING_VERSION = 0.1.0; PRODUCT_BUNDLE_IDENTIFIER = pl.apargb.milkyway.ios; PRODUCT_NAME = Milkyway; TARGETED_DEVICE_FAMILY = 1; SUPPORTED_PLATFORMS = "iphoneos iphonesimulator"; SUPPORTS_MACCATALYST = NO; SWIFT_EMIT_LOC_STRINGS = YES;'
    settings += ' SWIFT_OPTIMIZATION_LEVEL = "-Onone"; SWIFT_ACTIVE_COMPILATION_CONDITIONS = DEBUG; DEBUG_INFORMATION_FORMAT = dwarf;' if debug else ' SWIFT_OPTIMIZATION_LEVEL = "-O"; DEBUG_INFORMATION_FORMAT = "dwarf-with-dsym";'
    target_configs.append(obj("target:" + configuration, f'isa = XCBuildConfiguration; name = {configuration}; buildSettings = {{ {settings} }};'))
project_list = obj("project-list", f'isa = XCConfigurationList; buildConfigurations = {array(project_configs)}; defaultConfigurationIsVisible = 0; defaultConfigurationName = Release;')
target_list = obj("target-list", f'isa = XCConfigurationList; buildConfigurations = {array(target_configs)}; defaultConfigurationIsVisible = 0; defaultConfigurationName = Release;')
target = obj("target", f'isa = PBXNativeTarget; buildConfigurationList = {target_list}; buildPhases = {array([source_phase, framework_phase, resource_phase])}; buildRules = (); dependencies = (); name = Milkyway; productName = Milkyway; productReference = {product}; productType = "com.apple.product-type.application";')
root_object = obj("project", f'isa = PBXProject; attributes = {{ LastUpgradeCheck = 1600; }}; buildConfigurationList = {project_list}; compatibilityVersion = "Xcode 14.0"; developmentRegion = pl; hasScannedForEncodings = 0; knownRegions = (pl, en, Base); mainGroup = {group}; productRefGroup = {products}; projectDirPath = ""; projectRoot = ""; targets = {array([target])};')
output = '// !$*UTF8*$!\n{\n archiveVersion = 1; classes = {}; objectVersion = 56; objects = {\n'
output += ''.join(f'  {key} = {{ {value} }};\n' for key, value in objects.items())
output += f' }}; rootObject = {root_object};\n}}\n'
(project / "project.pbxproj").write_text(output)
scheme = ET.Element("Scheme", LastUpgradeVersion="1600", version="1.3")
build = ET.SubElement(scheme, "BuildAction", parallelizeBuildables="YES", buildImplicitDependencies="YES")
entries = ET.SubElement(build, "BuildActionEntries")
entry = ET.SubElement(entries, "BuildActionEntry", buildForTesting="YES", buildForRunning="YES", buildForProfiling="YES", buildForArchiving="YES", buildForAnalyzing="YES")
ref = dict(BuildableIdentifier="primary", BlueprintIdentifier=target, BuildableName="Milkyway.app", BlueprintName="Milkyway", ReferencedContainer="container:Milkyway.xcodeproj")
ET.SubElement(entry, "BuildableReference", **ref)
ET.SubElement(scheme, "TestAction", buildConfiguration="Debug", selectedDebuggerIdentifier="Xcode.DebuggerFoundation.Debugger.LLDB", selectedLauncherIdentifier="Xcode.IDEFoundation.Launcher.LLDB")
launch = ET.SubElement(scheme, "LaunchAction", buildConfiguration="Debug", selectedDebuggerIdentifier="Xcode.DebuggerFoundation.Debugger.LLDB", selectedLauncherIdentifier="Xcode.IDEFoundation.Launcher.LLDB", launchStyle="0", useCustomWorkingDirectory="NO", ignoresPersistentStateOnLaunch="NO", debugDocumentVersioning="YES", allowLocationSimulation="YES")
runnable = ET.SubElement(launch, "BuildableProductRunnable", runnableDebuggingMode="0")
ET.SubElement(runnable, "BuildableReference", **ref)
profile = ET.SubElement(scheme, "ProfileAction", buildConfiguration="Release", shouldUseLaunchSchemeArgsEnv="YES", savedToolIdentifier="", useCustomWorkingDirectory="NO", debugDocumentVersioning="YES")
ET.SubElement(ET.SubElement(profile, "BuildableProductRunnable", runnableDebuggingMode="0"), "BuildableReference", **ref)
ET.SubElement(scheme, "AnalyzeAction", buildConfiguration="Debug")
ET.SubElement(scheme, "ArchiveAction", buildConfiguration="Release", revealArchiveInOrganizer="YES")
destination = project / "xcshareddata/xcschemes"
destination.mkdir(parents=True, exist_ok=True)
ET.indent(scheme)
ET.ElementTree(scheme).write(destination / "Milkyway.xcscheme", encoding="utf-8", xml_declaration=True)
print("Generated Milkyway.xcodeproj and shared scheme.")
