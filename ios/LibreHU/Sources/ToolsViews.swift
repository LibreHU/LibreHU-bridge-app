import SwiftUI
import UniformTypeIdentifiers

/// Names and formats shared by the screens.
enum Labels {
    static func obd(_ k: String) -> String {
        let key = "obd_" + k.lowercased()
        let s = String(localized: String.LocalizationValue(key))
        return s == key ? k.replacingOccurrences(of: "_", with: " ").capitalized : s
    }

    static func obdValue(_ k: String, _ v: Any?) -> String {
        let raw = (v as? NSNumber)?.doubleValue ?? Double(String(describing: v ?? "")) ?? .nan
        if raw.isNaN { return "—" }
        switch k {
        case "SPEED": return String(format: "%.0f km/h", raw)
        case "RPM": return String(format: "%.0f", raw)
        case let x where x.hasSuffix("_TEMP"): return String(format: "%.0f °C", raw)
        case let x where x.hasPrefix("FUEL") || x == "LOAD" || x == "THROTTLE": return String(format: "%.0f %%", raw)
        default: return String(format: raw == raw.rounded() ? "%.0f" : "%.1f", raw)
        }
    }

    static func battery(_ b: [String: Any]) -> String {
        switch b.s("status") {
        case "CHARGING": return String(localized: "bat_charging")
        case "NOT_CHARGING": return String(localized: "bat_not_charging")
        case "OVERVOLTAGE": return String(localized: "bat_over")
        default: return b.d("voltage").map { String(format: "%.1f V", $0) } ?? ""
        }
    }

    static func thermal(_ type: String) -> String {
        let t = type.lowercased()
        if t.contains("cpu") || t.contains("soc") { return "CPU" }
        if t.contains("gpu") { return "GPU" }
        if t.contains("pmic") { return "PMIC" }
        if t.contains("bat") { return "Batt." }
        if t.contains("board") || t.contains("pcb") { return "Board" }
        return type.replacingOccurrences(of: "mtkts", with: "")
    }

    static func bytes(_ n: Double) -> String { ByteCountFormatter.string(fromByteCount: Int64(n), countStyle: .binary) }

    static func app(_ pageId: String) -> String {
        switch pageId.components(separatedBy: ":").first ?? "" {
        case "org.librehu.service": return "LibreHU-service"
        case "org.librehu.launcher": return "Launcher"
        case "org.librehu.fm": return "Radio FM"
        case "org.librehu.dialer": return "Dialer"
        case "org.librehu.widgets": return "Widgets"
        case "org.librehu.btnremap": return "BtnRemap"
        case "org.librehu.easysource": return "EasySource"
        case "org.librehu.disc": return "Disc"
        case "org.librehu.ota": return "LibreHU Apps"
        case "org.librehu.helper": return "Helper"
        case "org.librehu.headunit": return "Android Auto"
        case "org.librehu.carplay": return "CarPlay"
        case "com.llsl.viper4android": return "ViPER4Android"
        case let p: return p.components(separatedBy: ".").last ?? p
        }
    }

    static func pageIcon(_ name: String) -> String {
        switch name {
        case "settings": return "gearshape.fill"
        case "volume": return "speaker.wave.2.fill"
        case "light": return "lightbulb.fill"
        case "car": return "car.fill"
        case "camera": return "camera.fill"
        case "video": return "tv.fill"
        case "bluetooth": return "dot.radiowaves.left.and.right"
        case "phone": return "iphone"
        case "gps": return "location.fill"
        case "speed": return "gauge.with.dots.needle.67percent"
        case "weather": return "sun.max.fill"
        case "palette": return "paintpalette.fill"
        case "image": return "photo.fill"
        case "home": return "house.fill"
        case "apps", "widgets": return "square.grid.2x2.fill"
        case "clock": return "clock.fill"
        case "lock": return "lock.fill"
        case "sos": return "sos.circle.fill"
        case "tire": return "exclamationmark.tirepressure"
        case "radio": return "radio.fill"
        case "keys": return "keyboard"
        case "disc": return "opticaldisc"
        case "download": return "arrow.down.circle.fill"
        case "source": return "arrow.left.arrow.right"
        case "report": return "doc.text.magnifyingglass"
        case "equalizer": return "slider.vertical.3"
        case "carplay": return "car.fill"
        case "wifi": return "wifi"
        default: return "slider.horizontal.3"
        }
    }
}

// MARK: - Apps

/// The head unit's apps with their icons: search, system apps or not, a touch opens it there.
struct AppsView: View {
    @StateObject private var client = BridgeClient.shared
    @State private var apps: [(pkg: String, label: String, system: Bool, icon: UIImage?)] = []
    @State private var query = ""
    @State private var system = false
    @State private var message: String?

    var body: some View {
        ScrollView {
            Toggle("apps_system", isOn: $system).padding(.horizontal)
            if apps.isEmpty { ProgressView().padding() }
            LazyVGrid(columns: [GridItem(.adaptive(minimum: 84))], spacing: 16) {
                ForEach(apps.filter { (system || !$0.system) && (query.isEmpty || $0.label.localizedCaseInsensitiveContains(query)) }, id: \.pkg) { a in
                    Button {
                        Task {
                            do {
                                _ = try await client.call("launch", ["pkg": a.pkg])
                                message = String(format: String(localized: "apps_started"), a.label)
                            } catch { message = error.localizedDescription }
                        }
                    } label: {
                        VStack(spacing: 6) {
                            if let i = a.icon { Image(uiImage: i).resizable().frame(width: 56, height: 56) } else { Image(systemName: "app.fill").font(.system(size: 44)) }
                            Text(a.label).font(.caption).lineLimit(2).multilineTextAlignment(.center).foregroundStyle(.primary)
                        }
                    }
                }
            }.padding()
        }
        .searchable(text: $query, prompt: Text("apps_search"))
        .alert(message ?? "", isPresented: Binding(get: { message != nil }, set: { if !$0 { message = nil } })) { Button("ok") {} }
        .task {
            guard apps.isEmpty, let a = try? await client.call("apps", timeout: 60) as? [[String: Any]] else { return }
            apps = a.map { o in (o.s("pkg"), o.s("label"), o.b("system"), (try? BridgeCrypto.unb64(o.s("icon").replacingOccurrences(of: "+", with: "-").replacingOccurrences(of: "/", with: "_"))).flatMap(UIImage.init(data:))) }
        }
    }
}

// MARK: - Files

/// The head unit's files: storage and USB keys (the whole system only when allowed there); a file is saved on the
/// iPhone (Files), one is sent from it, renamed, deleted; new folder. Transfers in 512 KiB pieces.
struct FilesView: View {
    @StateObject private var client = BridgeClient.shared
    @State private var roots: [(String, String)] = []
    @State private var path = "/sdcard"
    @State private var entries: [[String: Any]]?
    @State private var error: String?
    @State private var progress: (String, Double)?
    @State private var exportFile: TempFile?
    @State private var importing = false
    @State private var renaming: [String: Any]?
    @State private var newName = ""
    @State private var newFolder = false
    @State private var deleting: [String: Any]?
    @State private var reload = 0

    private func child(_ n: String) -> String { path == "/" ? "/\(n)" : "\(path)/\(n)" }

    var body: some View {
        List {
            ScrollView(.horizontal, showsIndicators: false) {
                HStack {
                    ForEach(roots, id: \.0) { r in
                        Button { path = r.0 } label: {
                            Label(r.1 == "internal" ? String(localized: "files_internal") : r.1 == "system" ? String(localized: "files_root") : (r.0 as NSString).lastPathComponent, systemImage: r.1 == "removable" ? "externaldrive" : r.1 == "system" ? "exclamationmark.triangle" : "internaldrive")
                        }.buttonStyle(.bordered)
                    }
                }
            }
            HStack {
                Button { path = (path as NSString).deletingLastPathComponent.isEmpty ? "/" : (path as NSString).deletingLastPathComponent } label: { Image(systemName: "chevron.up") }
                    .disabled(path == "/" || roots.contains { $0.0 == path })
                Text(path).font(.footnote.monospaced()).lineLimit(1)
            }
            if let progress {
                VStack(alignment: .leading) { Text(progress.0).font(.caption); ProgressView(value: progress.1) }
            }
            if let error { Text(error).foregroundStyle(.red) }
            if entries == nil && error == nil { ProgressView() }
            ForEach((entries ?? []).indices, id: \.self) { i in
                let f = entries![i]
                Button {
                    if f.b("dir") { path = child(f.s("name")) } else { download(f) }
                } label: {
                    HStack {
                        Image(systemName: f.b("dir") ? "folder.fill" : "doc").foregroundStyle(f.b("dir") ? Color.accentColor : .secondary)
                        VStack(alignment: .leading) {
                            Text(f.s("name")).foregroundStyle(.primary).lineLimit(1)
                            if !f.b("dir") { Text(Labels.bytes(f.d("size") ?? 0)).font(.caption).foregroundStyle(.secondary) }
                        }
                    }
                }
                .swipeActions {
                    Button(role: .destructive) { deleting = f } label: { Label("files_delete", systemImage: "trash") }
                    Button { newName = f.s("name"); renaming = f } label: { Label("files_rename", systemImage: "pencil") }
                }
            }
        }
        .toolbar {
            ToolbarItemGroup(placement: .bottomBar) {
                Button { importing = true } label: { Label("files_upload", systemImage: "square.and.arrow.up") }.disabled(progress != nil)
                Spacer()
                Button { newName = ""; newFolder = true } label: { Label("files_new_folder", systemImage: "folder.badge.plus") }
            }
        }
        .task {
            if let a = try? await client.call("fs.roots") as? [[String: Any]] { roots = a.map { ($0.s("path"), $0.s("kind")) } }
        }
        .task(id: "\(path)#\(reload)") {
            entries = nil
            error = nil
            do { entries = try await client.call("fs.list", ["path": path], timeout: 30) as? [[String: Any]] ?? [] } catch { self.error = error.localizedDescription }
        }
        .sheet(item: $exportFile) { f in ShareSheet(url: f.url) }
        .fileImporter(isPresented: $importing, allowedContentTypes: [.item]) { result in
            if case let .success(url) = result { upload(url) }
        }
        .alert("files_rename", isPresented: Binding(get: { renaming != nil }, set: { if !$0 { renaming = nil } })) {
            TextField("", text: $newName)
            Button("ok") {
                if let f = renaming { Task { _ = try? await client.call("fs.rename", ["from": child(f.s("name")), "to": child(newName.replacingOccurrences(of: "/", with: "_"))]); reload += 1 } }
                renaming = nil
            }
            Button("cancel", role: .cancel) { renaming = nil }
        }
        .alert("files_new_folder", isPresented: $newFolder) {
            TextField("", text: $newName)
            Button("ok") { Task { _ = try? await client.call("fs.mkdir", ["path": child(newName.replacingOccurrences(of: "/", with: "_"))]); reload += 1 } }
            Button("cancel", role: .cancel) {}
        }
        .confirmationDialog(String(format: String(localized: "files_delete_title"), deleting?.s("name") ?? ""), isPresented: Binding(get: { deleting != nil }, set: { if !$0 { deleting = nil } }), titleVisibility: .visible) {
            Button("files_delete", role: .destructive) {
                if let f = deleting { Task { _ = try? await client.call("fs.delete", ["path": child(f.s("name"))], timeout: 120); reload += 1 } }
                deleting = nil
            }
        }
    }

    private func download(_ f: [String: Any]) {
        let name = f.s("name")
        let remote = child(name)
        progress = (String(format: String(localized: "files_receiving"), name), 0)
        Task {
            do {
                let url = FileManager.default.temporaryDirectory.appendingPathComponent(name)
                FileManager.default.createFile(atPath: url.path, contents: nil)
                let h = try FileHandle(forWritingTo: url)
                defer { try? h.close() }
                var offset = 0
                while true {
                    guard let r = try await client.call("fs.read", ["path": remote, "offset": offset, "length": 512 * 1024], timeout: 60) as? [String: Any] else { break }
                    let data = Data(base64Encoded: r.s("data")) ?? Data()
                    try h.write(contentsOf: data)
                    offset += data.count
                    let size = max(1, r.i("size"))
                    progress = (progress?.0 ?? "", Double(offset) / Double(size))
                    if r.b("eof") || data.isEmpty { break }
                }
                exportFile = TempFile(url: url)
            } catch { self.error = error.localizedDescription }
            progress = nil
        }
    }

    private func upload(_ url: URL) {
        let name = url.lastPathComponent.replacingOccurrences(of: "/", with: "_")
        progress = (String(format: String(localized: "files_sending"), name), 0)
        Task {
            let ok = url.startAccessingSecurityScopedResource()
            defer { if ok { url.stopAccessingSecurityScopedResource() } }
            do {
                let h = try FileHandle(forReadingFrom: url)
                defer { try? h.close() }
                let size = (try? FileManager.default.attributesOfItem(atPath: url.path)[.size] as? NSNumber)?.doubleValue ?? 0
                var offset = 0
                while true {
                    let piece = try h.read(upToCount: 256 * 1024) ?? Data()
                    if piece.isEmpty && offset > 0 { break }
                    _ = try await client.call("fs.write", ["path": child(name), "offset": offset, "data": piece.base64EncodedString()], timeout: 60)
                    offset += piece.count
                    progress = (progress?.0 ?? "", size > 0 ? Double(offset) / size : 0)
                    if piece.count < 256 * 1024 { break }
                }
            } catch { self.error = error.localizedDescription }
            progress = nil
            reload += 1
        }
    }
}

struct TempFile: Identifiable {
    let url: URL
    var id: String { url.path }
}

struct ShareSheet: UIViewControllerRepresentable {
    let url: URL
    func makeUIViewController(context: Context) -> UIActivityViewController { UIActivityViewController(activityItems: [url], applicationActivities: nil) }
    func updateUIViewController(_ c: UIActivityViewController, context: Context) {}
}

// MARK: - Tools

/// Restart (Android, recovery, fastboot), logs saved on the iPhone, Android's settings (every key), about.
struct ToolsView: View {
    let unit: HeadUnit
    @StateObject private var client = BridgeClient.shared
    @State private var reboot: String?
    @State private var busyLog: String?
    @State private var exportFile: TempFile?
    @State private var message: String?

    var body: some View {
        List {
            if !client.info.root { Text("tools_no_root").foregroundStyle(.red) }
            Section("tools_restart") {
                Button { reboot = "normal" } label: { Label("tools_reboot", systemImage: "arrow.clockwise") }
                Button { reboot = "recovery" } label: { Label("tools_recovery", systemImage: "lifepreserver") }
                Button { reboot = "bootloader" } label: { Label("tools_fastboot", systemImage: "cpu") }
            }
            if client.info.has("logs") {
                Section {
                    ForEach([("librehu", "logs_librehu"), ("logcat", "logs_logcat"), ("kernel", "logs_kernel")], id: \.0) { kind, label in
                        Button { fetchLog(kind) } label: { Label(LocalizedStringKey(label), systemImage: "doc.text") }.disabled(busyLog != nil)
                    }
                    if busyLog != nil { ProgressView() }
                } header: { Text("tools_logs") } footer: { Text("tools_logs_hint") }
            }
            if client.info.has("settings") {
                Section("tools_android") {
                    NavigationLink { AndroidSettingsView() } label: { Label("tools_android_editor", systemImage: "list.bullet.rectangle") }
                }
            }
            Section("tools_about") {
                LabeledContent("about_unit", value: unit.name)
                LabeledContent("about_fp", value: String(unit.fingerprint.prefix(16)))
                LabeledContent("about_api", value: "\(client.info.api) · bridge \(client.info.bridge)")
                LabeledContent("about_root", value: client.info.root ? String(localized: "yes") : String(localized: "no"))
                LabeledContent("about_root_files", value: client.info.rootFiles ? String(localized: "yes") : String(localized: "no"))
            }
        }
        .confirmationDialog(Text(LocalizedStringKey(reboot == "recovery" ? "tools_recovery" : reboot == "bootloader" ? "tools_fastboot" : "tools_reboot")), isPresented: Binding(get: { reboot != nil }, set: { if !$0 { reboot = nil } }), titleVisibility: .visible) {
            Button("tools_confirm", role: .destructive) {
                let mode = reboot ?? "normal"
                Task {
                    do { _ = try await client.call("reboot", ["mode": mode]); message = String(localized: "tools_rebooting") } catch { message = error.localizedDescription }
                }
                reboot = nil
            }
        } message: { Text(LocalizedStringKey(reboot == "bootloader" ? "tools_fastboot_hint" : "tools_reboot_hint")) }
        .alert(message ?? "", isPresented: Binding(get: { message != nil }, set: { if !$0 { message = nil } })) { Button("ok") {} }
        .sheet(item: $exportFile) { f in ShareSheet(url: f.url) }
    }

    private func fetchLog(_ kind: String) {
        busyLog = kind
        Task {
            do {
                guard let r = try await client.call("logs", ["kind": kind], timeout: 150) as? [String: Any] else { throw BridgeError("logs") }
                let remote = r.s("path")
                let url = FileManager.default.temporaryDirectory.appendingPathComponent((remote as NSString).lastPathComponent)
                FileManager.default.createFile(atPath: url.path, contents: nil)
                let h = try FileHandle(forWritingTo: url)
                var offset = 0
                while true {
                    guard let p = try await client.call("fs.read", ["path": remote, "offset": offset, "length": 512 * 1024], timeout: 60) as? [String: Any] else { break }
                    let data = Data(base64Encoded: p.s("data")) ?? Data()
                    try h.write(contentsOf: data)
                    offset += data.count
                    if p.b("eof") || data.isEmpty { break }
                }
                try h.close()
                exportFile = TempFile(url: url)
            } catch { message = error.localizedDescription }
            busyLog = nil
        }
    }
}

/// Every key of system, secure and global (root): search, change, add, delete.
struct AndroidSettingsView: View {
    @StateObject private var client = BridgeClient.shared
    @State private var ns = "system"
    @State private var rows: [(String, String)]?
    @State private var query = ""
    @State private var editing: (String, String)?
    @State private var key = ""
    @State private var value = ""
    @State private var reload = 0

    var body: some View {
        List {
            Text("editor_warning").font(.footnote).foregroundStyle(.secondary)
            Picker("", selection: $ns) { ForEach(["system", "secure", "global"], id: \.self) { Text($0) } }.pickerStyle(.segmented)
            if rows == nil { ProgressView() }
            ForEach((rows ?? []).filter { query.isEmpty || $0.0.localizedCaseInsensitiveContains(query) || $0.1.localizedCaseInsensitiveContains(query) }, id: \.0) { r in
                Button {
                    key = r.0
                    value = r.1
                    editing = r
                } label: {
                    VStack(alignment: .leading) {
                        Text(r.0).font(.footnote.monospaced()).foregroundStyle(.primary)
                        Text(r.1.isEmpty ? "∅" : r.1).font(.caption).foregroundStyle(.secondary).lineLimit(2)
                    }
                }
            }
        }
        .navigationTitle("tools_android_editor")
        .searchable(text: $query, prompt: Text("editor_search"))
        .toolbar { Button { key = ""; value = ""; editing = ("", "") } label: { Image(systemName: "plus") } }
        .task(id: "\(ns)#\(reload)") {
            rows = nil
            rows = ((try? await client.call("settings.list", ["ns": ns], timeout: 30)) as? [[String: Any]] ?? []).map { ($0.s("key"), $0.s("value")) }
        }
        .alert(editing.map { "\(ns) \($0.0.isEmpty ? String(localized: "editor_new") : $0.0)" } ?? "", isPresented: Binding(get: { editing != nil }, set: { if !$0 { editing = nil } })) {
            if editing?.0.isEmpty == true { TextField("editor_key", text: $key) }
            TextField("editor_value", text: $value)
            Button("editor_save") { Task { _ = try? await client.call("settings.put", ["ns": ns, "key": key, "value": value]); reload += 1 } }
            if editing?.0.isEmpty == false { Button("files_delete", role: .destructive) { Task { _ = try? await client.call("settings.delete", ["ns": ns, "key": key]); reload += 1 } } }
            Button("cancel", role: .cancel) {}
        }
    }
}
