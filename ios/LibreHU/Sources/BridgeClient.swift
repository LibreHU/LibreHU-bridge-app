import Foundation
import UIKit

enum LinkState: Equatable {
    case idle
    case connecting(String)
    case connected(String)
    case failed(String)
}

/// What the head unit's service offers (`hello`).
struct UnitInfo {
    var name = ""
    var api = 0
    var bridge = 1
    var features: Set<String> = []
    var root = false
    var rootFiles = false
    func has(_ f: String) -> Bool { features.contains(f) }
}

/// The link to one head unit over Wi-Fi: its addresses (then the iPhone hotspot's subnet), the encrypted channel, the
/// phone's authentication, then requests and the `values` events. iOS apps cannot open a Bluetooth serial link
/// (RFCOMM) to an accessory without Apple's MFi programme: Wi-Fi only (the iPhone's hotspot works).
@MainActor
final class BridgeClient: ObservableObject {
    static let shared = BridgeClient()

    @Published private(set) var state: LinkState = .idle
    @Published private(set) var values: [String: Any] = [:]
    @Published private(set) var info = UnitInfo()
    @Published private(set) var unit: HeadUnit?

    private var channel: BridgeChannel?
    private var pending: [Int: CheckedContinuation<[String: Any], Error>] = [:]
    private var nextId = 1

    private var phoneName: String { String(UIDevice.current.name.prefix(64)) }

    /// Addresses to try: the known ones, then the iPhone hotspot's (172.20.10.2–14) when the phone is the hotspot.
    private func candidates(_ ips: [String]) -> [String] {
        ips + (2...14).map { "172.20.10.\($0)" }.filter { !ips.contains($0) }
    }

    private func open(_ ips: [String], port: Int) async throws -> (TcpStream, String) {
        for ip in ips {
            if let s = try? await TcpStream.open(host: ip, port: port, timeout: ips.firstIndex(of: ip)! < 3 ? 3 : 0.6) { return (s, ip) }
        }
        throw BridgeError(String(localized: "error_unreachable"))
    }

    /// Pairs with the head unit of a scanned QR code.
    func pair(_ code: PairingCode) async throws -> HeadUnit {
        disconnect()
        state = .connecting(code.name)
        do {
            let (stream, ip) = try await open(candidates(code.ips), port: code.port)
            let ch = try await BridgeChannel.connect(stream, pinned: code.fingerprint)
            try await ch.send(["mode": "qr", "name": phoneName, "proof": BridgeCrypto.b64(BridgeCrypto.hmac(key: code.token, data: ch.transcript))])
            let r = try await ch.receive()
            guard r["ok"] as? Bool == true, let id = r["id"] as? String, let cred = r["credential"] as? String else {
                ch.close()
                throw BridgeError(r["e"] as? String ?? "refused")
            }
            let u = HeadUnit(fingerprint: code.fingerprint, name: r["unit"] as? String ?? code.name, deviceId: id, ips: [ip] + code.ips.filter { $0 != ip }, port: code.port)
            Units.shared.save(u, credential: try BridgeCrypto.unb64(cred))
            started(ch, u, via: ip)
            return u
        } catch {
            state = .failed(error.localizedDescription)
            throw error
        }
    }

    /// Connects to a paired head unit (nothing when already connected to it).
    func connect(_ u: HeadUnit) async -> Bool {
        if case .connected = state, unit?.fingerprint == u.fingerprint, channel != nil { return true }
        disconnect()
        state = .connecting(u.name)
        do {
            guard let cred = Units.shared.credential(u.fingerprint) else { throw BridgeError(String(localized: "error_pair_again")) }
            let (stream, ip) = try await open(candidates(u.ips), port: u.port)
            let ch = try await BridgeChannel.connect(stream, pinned: u.fingerprint)
            try await ch.send(["mode": "cred", "id": u.deviceId, "name": phoneName, "proof": BridgeCrypto.b64(BridgeCrypto.hmac(key: cred, data: ch.transcript))])
            let r = try await ch.receive()
            guard r["ok"] as? Bool == true else {
                ch.close()
                throw BridgeError(r["e"] as? String ?? "refused")
            }
            var known = u
            known.name = r["unit"] as? String ?? u.name
            if known.ips.first != ip { known.ips = [ip] + u.ips.filter { $0 != ip } }
            Units.shared.save(known)
            started(ch, known, via: ip)
            return true
        } catch {
            state = .failed(error.localizedDescription)
            return false
        }
    }

    private func started(_ ch: BridgeChannel, _ u: HeadUnit, via: String) {
        channel = ch
        unit = u
        state = .connected("Wi-Fi \(via)")
        Task { await readLoop(ch) }
        Task {
            try? await ch.send(["id": 0, "m": "subscribe"])
            if let o = try? await call("hello") as? [String: Any] {
                info = UnitInfo(
                    name: o["unit"] as? String ?? "",
                    api: o["api"] as? Int ?? 0,
                    bridge: max(1, o["bridge"] as? Int ?? 1),
                    features: Set(o["features"] as? [String] ?? []),
                    root: o["root"] as? Bool ?? false,
                    rootFiles: o["rootFiles"] as? Bool ?? false
                )
            }
        }
    }

    private func readLoop(_ ch: BridgeChannel) async {
        do {
            while channel === ch {
                let m = try await ch.receive()
                if let ev = m["ev"] as? String {
                    if ev == "values" { values = m["d"] as? [String: Any] ?? [:] }
                    continue
                }
                guard let id = m["id"] as? Int, let c = pending.removeValue(forKey: id) else { continue }
                c.resume(returning: m)
            }
        } catch {
            if channel === ch {
                close()
                state = .failed(error.localizedDescription)
            }
        }
    }

    /// One request; throws the head unit's error.
    func call(_ method: String, _ params: [String: Any] = [:], timeout: TimeInterval = 15) async throws -> Any {
        guard let ch = channel else { throw BridgeError(String(localized: "error_not_connected")) }
        let id = nextId
        nextId += 1
        let reply: [String: Any] = try await withCheckedThrowingContinuation { cont in
            pending[id] = cont
            Task {
                do {
                    try await ch.send(["id": id, "m": method, "p": params])
                } catch {
                    if let c = pending.removeValue(forKey: id) { c.resume(throwing: error) }
                }
            }
            Task {
                try? await Task.sleep(nanoseconds: UInt64(timeout * 1_000_000_000))
                if let c = pending.removeValue(forKey: id) { c.resume(throwing: BridgeError("timeout")) }
            }
        }
        if let e = reply["e"] { throw BridgeError(e as? String ?? "error") }
        return reply["r"] ?? true
    }

    func disconnect() {
        close()
        state = .idle
    }

    private func close() {
        channel?.close()
        channel = nil
        for (_, c) in pending { c.resume(throwing: BridgeError("closed")) }
        pending.removeAll()
        values = [:]
        info = UnitInfo()
    }
}
