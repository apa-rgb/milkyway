// swift-tools-version: 5.9
import PackageDescription
let package = Package(name: "MilkywayCore", platforms: [.iOS(.v17), .macOS(.v13)], products: [
    .library(name: "MilkywayCore", targets: ["MilkywayCore"])
], targets: [
    .target(name: "MilkywayCore", path: "Sources/Core"),
    .testTarget(name: "MilkywayCoreTests", dependencies: ["MilkywayCore"])
])
