import SwiftUI

/// JSON helpers on the head unit's answers.
extension Dictionary where Key == String, Value == Any {
    func s(_ k: String) -> String {
        if let v = self[k] as? String { return v }
        if let v = self[k] as? NSNumber { return v.stringValue }
        return ""
    }

    func i(_ k: String) -> Int { (self[k] as? NSNumber)?.intValue ?? Int(s(k)) ?? 0 }
    func d(_ k: String) -> Double? { (self[k] as? NSNumber)?.doubleValue }
    func b(_ k: String) -> Bool { (self[k] as? Bool) ?? ((self[k] as? NSNumber)?.boolValue ?? false) }
    func o(_ k: String) -> [String: Any]? { self[k] as? [String: Any] }
    func a(_ k: String) -> [[String: Any]] { self[k] as? [[String: Any]] ?? [] }
}

/// One head unit: connected on opening, then five tabs as on Android.
struct UnitView: View {
    let unit: HeadUnit
    @StateObject private var client = BridgeClient.shared
    @State private var tab = 0

    var body: some View {
        TabView(selection: $tab) {
            DashboardView().tabItem { Label("tab_home", systemImage: "speedometer") }.tag(0)
            SettingsTabView().tabItem { Label("tab_settings", systemImage: "slider.horizontal.3") }.tag(1)
            if client.info.has("apps") { AppsView().tabItem { Label("tab_apps", systemImage: "square.grid.3x3.fill") }.tag(2) }
            if client.info.has("files") { FilesView().tabItem { Label("tab_files", systemImage: "folder.fill") }.tag(3) }
            if client.info.has("reboot") { ToolsView(unit: unit).tabItem { Label("tab_tools", systemImage: "wrench.and.screwdriver.fill") }.tag(4) }
        }
        .navigationTitle(unit.name)
        .navigationBarTitleDisplayMode(.inline)
        .safeAreaInset(edge: .top) { LinkBanner(unit: unit) }
        .task { _ = await client.connect(unit) }
    }
}

/// The link's state, with a retry.
struct LinkBanner: View {
    let unit: HeadUnit
    @StateObject private var client = BridgeClient.shared

    var body: some View {
        switch client.state {
        case let .connecting(name):
            HStack { ProgressView(); Text(String(format: String(localized: "connecting"), name)).font(.footnote) }.padding(8).frame(maxWidth: .infinity).background(.thinMaterial)
        case let .failed(message):
            HStack {
                Image(systemName: "exclamationmark.triangle.fill").foregroundStyle(.red)
                Text(String(format: String(localized: "link_failed"), message)).font(.footnote)
                Spacer()
                Button("retry") { Task { _ = await client.connect(unit) } }
            }.padding(8).background(.thinMaterial)
        default:
            EmptyView()
        }
    }
}

// MARK: - Home

/// Battery, GPS (as on LibreHU Launcher: searching in yellow, fix in green), temperatures and system, OBD, media keys.
struct DashboardView: View {
    @StateObject private var client = BridgeClient.shared
    private let searching = Color(red: 0.99, green: 0.84, blue: 0.39)
    private let fixed = Color(red: 0.51, green: 0.79, blue: 0.58)

    var body: some View {
        ScrollView {
            VStack(spacing: 16) {
                battery
                if let g = client.values.o("gps") { gps(g) }
                if let s = client.values.o("system") { system(s) }
                if let obd = client.values.o("obd")?.filter({ $0.key != "BATTERY" }), !obd.isEmpty {
                    LazyVGrid(columns: [GridItem(.flexible()), GridItem(.flexible())], spacing: 12) {
                        ForEach(obd.keys.sorted(), id: \.self) { k in
                            Card {
                                VStack(alignment: .leading, spacing: 4) {
                                    Text(Labels.obdValue(k, obd[k])).font(.title2.bold())
                                    Text(Labels.obd(k)).font(.caption).foregroundStyle(.secondary)
                                }.frame(maxWidth: .infinity, alignment: .leading)
                            }
                        }
                    }
                }
                if case .connected = client.state { media }
                if client.info.bridge < 2, case .connected = client.state { Text("update_service").font(.footnote).foregroundStyle(.secondary) }
            }.padding()
        }
        .task {
            if client.info.has("gps") { _ = try? await client.call("gps.watch", ["on": true]) }
        }
        .onDisappear { Task { _ = try? await client.call("gps.watch", ["on": false]) } }
    }

    private var battery: some View {
        let b = client.values.o("battery")
        let volts = b?.d("voltage")
        let percent = b.flatMap { $0["percent"] as? NSNumber }?.intValue
        let fraction = percent.map { Double($0) / 100 } ?? volts.map { min(max(($0 - 11.5) / 3.5, 0), 1) } ?? 0
        return Card {
            HStack(spacing: 20) {
                Ring(fraction: fraction, color: .accentColor) {
                    VStack {
                        Text(percent.map { "\($0) %" } ?? volts.map { String(format: "%.1f", $0) } ?? "—").font(.title3.bold())
                        if percent == nil, volts != nil { Text("V").font(.caption).foregroundStyle(.secondary) }
                    }
                }
                VStack(alignment: .leading, spacing: 4) {
                    Text("battery").font(.headline)
                    Text(b.map { Labels.battery($0) } ?? String(localized: "battery_none")).font(.subheadline).foregroundStyle(.secondary)
                }
                Spacer()
            }
        }
    }

    private func gps(_ g: [String: Any]) -> some View {
        let used = g.i("used"), visible = g.i("visible")
        let on = g.b("enabled"), fix = g.b("fix")
        let tint: Color = !on ? .secondary : fix ? fixed : searching
        let details = [
            g["accuracyM"] != nil ? "±\(g.i("accuracyM")) m" : nil,
            g["speedKmh"] != nil ? "\(g.i("speedKmh")) km/h" : nil,
            g["altitudeM"] != nil ? "\(g.i("altitudeM")) m" : nil,
            g["firstFixMs"] != nil ? String(format: "TTFF %.1f s", Double(g.i("firstFixMs")) / 1000) : nil,
            used > 0 ? "\(g.i("cn0")) dB-Hz" : nil,
        ].compactMap { $0 }
        return Card {
            VStack(alignment: .leading, spacing: 12) {
                HStack(spacing: 20) {
                    Ring(fraction: visible > 0 ? Double(used) / Double(visible) : 0, color: tint) {
                        VStack { Text("\(used)/\(visible)").font(.title3.bold()); Text("gps_sats").font(.caption2).foregroundStyle(.secondary) }
                    }
                    VStack(alignment: .leading, spacing: 4) {
                        Label("GPS", systemImage: !on ? "location.slash" : fix ? "location.fill" : "location").font(.headline).foregroundStyle(tint)
                        Text(!on ? "gps_off" : fix ? "gps_fix" : "gps_searching").font(.subheadline).foregroundStyle(.secondary)
                        if !details.isEmpty { Text(details.joined(separator: " · ")).font(.caption).foregroundStyle(.secondary) }
                    }
                    Spacer()
                }
                let cons = g.a("constellations")
                if !cons.isEmpty {
                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack { ForEach(cons.indices, id: \.self) { i in Text("\(cons[i].s("name")) \(cons[i].i("used"))/\(cons[i].i("visible"))").font(.caption).padding(.horizontal, 10).padding(.vertical, 5).background(Capsule().fill(Color.secondary.opacity(0.15))).foregroundStyle(cons[i].i("used") > 0 ? fixed : .secondary) } }
                    }
                }
                let sats = g.a("sats")
                if !sats.isEmpty {
                    GeometryReader { geo in
                        HStack(alignment: .bottom, spacing: 2) {
                            ForEach(sats.indices, id: \.self) { i in
                                RoundedRectangle(cornerRadius: 1.5).fill(sats[i].b("used") ? tint : Color.secondary.opacity(0.35))
                                    .frame(height: max(2, geo.size.height * min(Double(sats[i].i("cn0")) / 50, 1)))
                            }
                        }.frame(maxHeight: .infinity, alignment: .bottom)
                    }.frame(height: 56)
                }
            }
        }
    }

    private func system(_ s: [String: Any]) -> some View {
        let temps = s.a("temps").compactMap { t -> (String, Double)? in t.d("c").map { (Labels.thermal(t.s("name")), $0) } }.sorted { $0.1 > $1.1 }
        func colour(_ t: Double) -> Color { t >= 80 ? .red : t >= 60 ? searching : fixed }
        let mhz = (s["cpuMhz"] as? [NSNumber] ?? []).map(\.intValue).filter { $0 > 0 }
        let up = s.i("uptimeS")
        return Card {
            VStack(alignment: .leading, spacing: 10) {
                HStack {
                    Label("system", systemImage: "thermometer.medium").font(.headline)
                    Spacer()
                    if let t = temps.first { Text(String(format: "%.0f °C", t.1)).font(.title2.bold()).foregroundStyle(colour(t.1)) }
                }
                if temps.isEmpty { Text("system_no_temps").font(.caption).foregroundStyle(.secondary) }
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack { ForEach(temps.indices, id: \.self) { i in Text(String(format: "%@ %.0f °C", temps[i].0, temps[i].1)).font(.caption).padding(.horizontal, 10).padding(.vertical, 5).background(Capsule().fill(Color.secondary.opacity(0.15))).foregroundStyle(colour(temps[i].1)) } }
                }
                Text([mhz.isEmpty ? nil : "\(mhz.count) × \(mhz.max()!) MHz", (s["load"] as? [NSNumber])?.first.map { String(format: "load %.2f", $0.doubleValue) }, String(format: "%d h %02d", up / 3600, up % 3600 / 60)].compactMap { $0 }.joined(separator: " · ")).font(.caption).foregroundStyle(.secondary)
                Usage(label: String(localized: "system_memory"), used: s.d("memTotal").map { $0 - (s.d("memFree") ?? 0) } ?? 0, total: s.d("memTotal") ?? 0)
                if let d = s.o("data") { Usage(label: "/data", used: (d.d("total") ?? 0) - (d.d("free") ?? 0), total: d.d("total") ?? 0) }
                if let d = s.o("sdcard") { Usage(label: String(localized: "system_sdcard"), used: (d.d("total") ?? 0) - (d.d("free") ?? 0), total: d.d("total") ?? 0) }
            }
        }
    }

    private var media: some View {
        Card {
            VStack(spacing: 14) {
                HStack(spacing: 28) {
                    keyButton("PREVIOUS", "backward.fill", 22)
                    keyButton("PLAY_PAUSE", "playpause.fill", 30)
                    keyButton("NEXT", "forward.fill", 22)
                }
                HStack(spacing: 28) {
                    keyButton("VOLUME_DOWN", "speaker.minus.fill", 18)
                    keyButton("MUTE", "speaker.slash.fill", 18)
                    keyButton("VOLUME_UP", "speaker.plus.fill", 18)
                }
            }.frame(maxWidth: .infinity)
        }
    }

    private func keyButton(_ action: String, _ icon: String, _ size: CGFloat) -> some View {
        Button { Task { _ = try? await client.call("key", ["action": action]) } } label: {
            Image(systemName: icon).font(.system(size: size)).frame(width: size * 2.4, height: size * 2.4).background(Circle().fill(Color.accentColor.opacity(0.18)))
        }
    }
}

struct Card<Content: View>: View {
    @ViewBuilder let content: Content
    var body: some View {
        content.padding(16).frame(maxWidth: .infinity, alignment: .leading).background(RoundedRectangle(cornerRadius: 20).fill(Color(.secondarySystemBackground)))
    }
}

struct Ring<Content: View>: View {
    let fraction: Double
    let color: Color
    @ViewBuilder let content: Content
    var body: some View {
        ZStack {
            Circle().trim(from: 0, to: 0.75).stroke(Color.secondary.opacity(0.2), style: StrokeStyle(lineWidth: 10, lineCap: .round)).rotationEffect(.degrees(135))
            Circle().trim(from: 0, to: 0.75 * fraction).stroke(color, style: StrokeStyle(lineWidth: 10, lineCap: .round)).rotationEffect(.degrees(135))
            content
        }.frame(width: 96, height: 96)
    }
}

struct Usage: View {
    let label: String
    let used: Double
    let total: Double
    var body: some View {
        if total > 0 {
            VStack(alignment: .leading, spacing: 3) {
                HStack { Text(label).font(.caption); Spacer(); Text("\(Labels.bytes(used)) / \(Labels.bytes(total))").font(.caption).foregroundStyle(.secondary) }
                ProgressView(value: min(used / total, 1)).tint(used / total > 0.9 ? .red : .accentColor)
            }
        }
    }
}

// MARK: - Settings

/// Every settings page, by app, and a search through all their items.
struct SettingsTabView: View {
    @StateObject private var client = BridgeClient.shared
    @State private var pages: [[String: Any]] = []
    @State private var query = ""
    @State private var index: [(page: [String: Any], label: String, hint: String)]?

    var body: some View {
        List {
            if !query.isEmpty {
                if let index {
                    let q = query.lowercased()
                    let hits = index.filter { $0.label.lowercased().contains(q) || $0.hint.lowercased().contains(q) || $0.page.s("title").lowercased().contains(q) }
                    if hits.isEmpty { Text(String(format: String(localized: "settings_nothing"), query)).foregroundStyle(.secondary) }
                    ForEach(hits.indices, id: \.self) { i in
                        NavigationLink { PageView(id: hits[i].page.s("id"), title: hits[i].page.s("title")) } label: {
                            VStack(alignment: .leading) { Text(hits[i].label); Text("\(Labels.app(hits[i].page.s("id"))) › \(hits[i].page.s("title"))").font(.caption).foregroundStyle(.secondary) }
                        }
                    }
                } else {
                    HStack { ProgressView(); Text("settings_indexing") }
                }
            } else {
                let groups = Dictionary(grouping: pages.indices, by: { Labels.app(pages[$0].s("id")) })
                ForEach(groups.keys.sorted { a, b in a == "LibreHU-service" || (b != "LibreHU-service" && a < b) }, id: \.self) { app in
                    Section(app) {
                        ForEach(groups[app]!, id: \.self) { i in
                            NavigationLink { PageView(id: pages[i].s("id"), title: pages[i].s("title")) } label: {
                                Label(pages[i].s("title"), systemImage: Labels.pageIcon(pages[i].s("icon")))
                            }
                        }
                    }
                }
            }
        }
        .searchable(text: $query, prompt: Text("settings_search"))
        .task(id: client.state) {
            if case .connected = client.state, let a = try? await client.call("pages") as? [[String: Any]] { pages = a }
        }
        .task(id: query.isEmpty) {
            guard !query.isEmpty, index == nil, !pages.isEmpty else { return }
            var all: [(page: [String: Any], label: String, hint: String)] = []
            for p in pages {
                if let o = try? await client.call("page", ["id": p.s("id")], timeout: 30) as? [String: Any] {
                    for it in o.a("items") where it.s("type") != "section" { all.append((p, it.s("label"), it.s("hint"))) }
                }
            }
            index = all
        }
    }
}

/// One page as the head unit describes it: each item from its type, each change sent at once, the page read again.
struct PageView: View {
    let id: String
    let title: String
    @StateObject private var client = BridgeClient.shared
    @State private var items: [[String: Any]] = []
    @State private var error: String?
    @State private var revision = 0

    var body: some View {
        let groups: [(String, [[String: Any]])] = items.reduce(into: []) { acc, it in
            if it.s("type") == "section" { acc.append((it.s("label"), [])) } else {
                if acc.isEmpty { acc.append(("", [])) }
                acc[acc.count - 1].1.append(it)
            }
        }.filter { !$0.1.isEmpty }
        List {
            if let error { Text(error).foregroundStyle(.red) }
            if items.isEmpty && error == nil { ProgressView() }
            ForEach(groups.indices, id: \.self) { g in
                Section(groups[g].0) {
                    ForEach(groups[g].1.indices, id: \.self) { i in ItemView(item: groups[g].1[i], send: send) }
                }
            }
        }
        .navigationTitle(title)
        .task(id: revision) {
            do {
                let o = try await client.call("page", ["id": id], timeout: 30) as? [String: Any]
                items = o?.a("items") ?? []
                error = nil
            } catch { self.error = error.localizedDescription }
        }
    }

    private func send(_ method: String, _ item: [String: Any], _ value: Any?) {
        Task {
            var p: [String: Any] = ["page": id, "item": item.s("id")]
            if let value { p["value"] = value }
            do {
                _ = try await client.call(method, p)
                revision += 1
            } catch { self.error = error.localizedDescription }
        }
    }
}

struct ItemView: View {
    let item: [String: Any]
    let send: (String, [String: Any], Any?) -> Void
    @State private var slider: Double?
    @State private var editing = false
    @State private var text = ""
    @State private var confirm = false

    var body: some View {
        let label = item.s("label"), hint = item.s("hint")
        switch item.s("type") {
        case "info":
            VStack(alignment: .leading) {
                Text(label).font(.subheadline).foregroundStyle(.secondary)
                if !item.s("value").isEmpty { Text(item.s("value")).font(.headline).foregroundStyle(.tint) }
            }
        case "switch":
            Toggle(isOn: Binding(get: { item.b("value") }, set: { send("set", item, $0) })) {
                VStack(alignment: .leading) { Text(label); if !hint.isEmpty { Text(hint).font(.caption).foregroundStyle(.secondary) } }
            }
        case "slider":
            let min = Double(item.i("min")), max = Double(item.i("max")), step = Double(Swift.max(1, item.i("step")))
            let v = slider ?? Double(item.i("value"))
            VStack(alignment: .leading) {
                HStack {
                    Text(label)
                    Spacer()
                    Text(slider == nil && !item.s("display").isEmpty ? item.s("display") : "\(Int(v)) \(item.s("unit"))").foregroundStyle(.tint)
                }
                Slider(value: Binding(get: { v }, set: { slider = $0 }), in: min...Swift.max(min + 1, max), step: step) { editing in
                    if !editing, let s = slider { send("set", item, Int(s)); slider = nil }
                }
                if !hint.isEmpty { Text(hint).font(.caption).foregroundStyle(.secondary) }
            }
        case "choice":
            let options = item.a("options")
            Picker(selection: Binding(get: { item.s("value") }, set: { send("set", item, $0) })) {
                ForEach(options.indices, id: \.self) { i in Text(options[i].s("l")).tag(options[i].s("v")) }
            } label: {
                VStack(alignment: .leading) { Text(label); if !hint.isEmpty { Text(hint).font(.caption).foregroundStyle(.secondary) } }
            }
        case "text":
            Button {
                text = item.s("value")
                editing = true
            } label: {
                VStack(alignment: .leading) {
                    Text(label).foregroundStyle(.primary)
                    Text(item.s("value").isEmpty ? "—" : item.s("value")).foregroundStyle(.tint)
                    if !hint.isEmpty { Text(hint).font(.caption).foregroundStyle(.secondary) }
                }
            }
            .alert(label, isPresented: $editing) {
                TextField(label, text: $text)
                Button("ok") { send("set", item, String(text.prefix(Swift.max(1, item.i("max"))))) }
                Button("cancel", role: .cancel) {}
            }
        case "button":
            Button(label) { if item.b("confirm") { confirm = true } else { send("action", item, nil) } }
                .confirmationDialog(label, isPresented: $confirm, titleVisibility: .visible) {
                    Button(label, role: .destructive) { send("action", item, nil) }
                } message: { Text(hint.isEmpty ? String(localized: "confirm_action") : hint) }
        default:
            Text(label)
        }
    }
}
