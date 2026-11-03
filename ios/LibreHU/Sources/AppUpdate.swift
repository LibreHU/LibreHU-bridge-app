import Foundation

/// This app's newest build on GitHub: `release-ios.txt` of the CI's rolling prerelease `latest-ios` (build number =
/// number of commits, CFBundleVersion of the ipa). Read as a release download, not through the GitHub API: no limit of
/// requests per hour.
enum AppUpdate {
    private static let base = "https://github.com/LibreHU/LibreHU-bridge-app/releases/download/latest-ios"

    struct Release {
        let build: Int
        let name: String
        /// The ipa, downloaded by Safari, then installed with AltStore or Sideloadly.
        let ipa: URL
        var newer: Bool { build > AppUpdate.installedBuild }
    }

    /// Build number of this app (CURRENT_PROJECT_VERSION set by the CI).
    static var installedBuild: Int { Int(Bundle.main.infoDictionary?["CFBundleVersion"] as? String ?? "") ?? 0 }
    static var installedName: String { Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "?" }

    static func latest() async throws -> Release {
        let request = URLRequest(url: URL(string: "\(base)/release-ios.txt")!, cachePolicy: .reloadIgnoringLocalCacheData, timeoutInterval: 15)
        let (data, response) = try await URLSession.shared.data(for: request)
        guard let http = response as? HTTPURLResponse else { throw BridgeError("GitHub") }
        guard http.statusCode == 200, let text = String(data: data, encoding: .utf8) else { throw BridgeError("HTTP \(http.statusCode)") }
        // key=value lines, then a blank line and the notes (last commit's title).
        var kv: [String: String] = [:]
        for line in text.split(separator: "\n", omittingEmptySubsequences: false) {
            if line.trimmingCharacters(in: .whitespaces).isEmpty { break }
            let p = line.split(separator: "=", maxSplits: 1)
            if p.count == 2 { kv[String(p[0])] = String(p[1]).trimmingCharacters(in: .whitespaces) }
        }
        guard let build = kv["versionCode"].flatMap({ Int($0) }) else { throw BridgeError("release-ios.txt") }
        let ipa = URL(string: "\(base)/\(kv["ipa"] ?? "LibreHU-iOS-main.ipa")")!
        return Release(build: build, name: kv["versionName"] ?? "1.0.\(build)", ipa: ipa)
    }
}
