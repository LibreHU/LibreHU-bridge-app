import Foundation
import Network

/// TCP to the head unit (Network.framework), with async reads of exactly `n` bytes.
final class TcpStream: ByteStream {
    private let connection: NWConnection
    private let queue = DispatchQueue(label: "librehu-tcp")

    private init(_ c: NWConnection) { connection = c }

    /// Connects to `host`:`port` within `timeout` seconds, or fails.
    static func open(host: String, port: Int, timeout: TimeInterval = 3) async throws -> TcpStream {
        guard let p = NWEndpoint.Port(rawValue: UInt16(port)) else { throw BridgeError("port") }
        let options = NWProtocolTCP.Options()
        options.noDelay = true
        options.connectionTimeout = Int(timeout.rounded(.up))
        let c = NWConnection(host: NWEndpoint.Host(host), port: p, using: NWParameters(tls: nil, tcp: options))
        let s = TcpStream(c)
        try await withCheckedThrowingContinuation { (cont: CheckedContinuation<Void, Error>) in
            let done = Once()
            c.stateUpdateHandler = { state in
                switch state {
                case .ready:
                    done.run { cont.resume() }
                case let .failed(e):
                    done.run { cont.resume(throwing: e) }
                case let .waiting(e):
                    // No route, refused…: give up at once rather than waiting for a network change.
                    done.run {
                        c.cancel()
                        cont.resume(throwing: e)
                    }
                case .cancelled:
                    done.run { cont.resume(throwing: BridgeError("cancelled")) }
                default:
                    break
                }
            }
            c.start(queue: s.queue)
            s.queue.asyncAfter(deadline: .now() + timeout) {
                done.run {
                    c.cancel()
                    cont.resume(throwing: BridgeError("timeout"))
                }
            }
        }
        return s
    }

    func read(_ n: Int) async throws -> Data {
        try await withCheckedThrowingContinuation { cont in
            connection.receive(minimumIncompleteLength: n, maximumLength: n) { data, _, complete, error in
                if let error { cont.resume(throwing: error); return }
                if let data, data.count == n { cont.resume(returning: data); return }
                cont.resume(throwing: BridgeError(complete ? "closed" : "short read"))
            }
        }
    }

    func write(_ d: Data) async throws {
        try await withCheckedThrowingContinuation { (cont: CheckedContinuation<Void, Error>) in
            connection.send(content: d, completion: .contentProcessed { error in
                if let error { cont.resume(throwing: error) } else { cont.resume() }
            })
        }
    }

    func close() { connection.cancel() }
}

/// Runs a block once, whatever thread asks first.
final class Once {
    private let lock = NSLock()
    private var done = false
    func run(_ block: () -> Void) {
        lock.lock()
        if done { lock.unlock(); return }
        done = true
        lock.unlock()
        block()
    }
}
