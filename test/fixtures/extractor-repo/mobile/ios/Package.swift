// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "PanelsKit",
    dependencies: [
        .package(url: "https://github.com/apple/swift-collections", from: "1.0.0")
    ],
    targets: [
        .target(name: "PanelsKit"),
        .testTarget(name: "PanelsKitTests", dependencies: ["PanelsKit"])
    ]
)
