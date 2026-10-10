import SwiftUI

enum Catalog {
    static let tanks: [Tank] = {
        guard let url = Bundle.main.url(forResource: "tanks", withExtension: "json"), let data = try? Data(contentsOf: url) else { return [] }
        return (try? JSONDecoder().decode([Tank].self, from: data)) ?? []
    }()
    static let departments = ["Odbieralnia", "Aparatownia", "Masłownia", "Proszkownia", "Oleje"]
    static func color(_ department: String) -> Color {
        if department.hasPrefix("Odbieralnia") { return .blue }; if department.hasPrefix("Aparatownia") { return .teal }
        if department.hasPrefix("Masłownia") { return .orange }; if department.hasPrefix("Proszkownia") { return .purple }; return .indigo
    }
}
func display(_ value: Decimal?) -> String {
    guard let value else { return "—" }
    let formatter = NumberFormatter(); formatter.locale = Locale(identifier: "pl_PL"); formatter.numberStyle = .decimal; formatter.maximumFractionDigits = 3
    return formatter.string(from: NSDecimalNumber(decimal: value)) ?? string(value)
}
struct TanksView: View {
    @EnvironmentObject var store: CloudStore
    var laboratory = false
    @State private var department = "Odbieralnia"; @State private var selected: Tank?
    var body: some View {
        VStack(spacing: 8) {
            ScrollView(.horizontal, showsIndicators: false) { HStack(spacing: 6) { ForEach(Catalog.departments, id: \.self) { d in
                Button(d) { department = d }.font(.caption.bold()).padding(.horizontal, 10).padding(.vertical, 10).foregroundStyle(Catalog.color(d)).background(Catalog.color(d).opacity(d == department ? 0.22 : 0.06), in: RoundedRectangle(cornerRadius: 10)).overlay(RoundedRectangle(cornerRadius: 10).stroke(Catalog.color(d).opacity(0.35)))
            } }.padding(.horizontal) }
            if laboratory && store.role != "laboratory" { Text("Konto operatora: podgląd. Pomiary oznaczone przez laboratorium wymagają uprawnienia laboratory w Firebase.").font(.caption).foregroundStyle(.orange).padding(.horizontal) }
            ScrollView { LazyVStack(spacing: 6) {
                if !store.ready.contains(.inventory) { ProgressView("Ładowanie…") }
                ForEach(Catalog.tanks.filter { $0.group.hasPrefix(department) }) { tank in
                    TankCard(tank: tank).contentShape(Rectangle()).onTapGesture { selected = tank }
                }
            }.padding(.horizontal).padding(.bottom) }
        }.navigationTitle(laboratory ? "Laboratorium" : "Zbiorniki")
            .sheet(item: $selected) { tank in TankEditor(tank: tank, laboratory: laboratory) }
    }
}
struct TankCard: View {
    @EnvironmentObject var store: CloudStore
    let tank: Tank
    @State private var reset = false
    var row: Row { store.document(.inventory).row("tank_states", tank.id) ?? [:] }
    var batch: OilBatch { Operations.oilBatch(store.document(.inventory), tankID: tank.id) }
    var tone: Color { let urgency = batch.urgency(litres: number(row, "litres"), now: Date()); return tank.oil && urgency > 0 ? (urgency == 2 ? Color(red: 0.5, green: 0.05, blue: 0.18) : .red) : Catalog.color(tank.group) }
    var body: some View {
        VStack(alignment: .leading, spacing: 5) {
            HStack {
                Text(tank.name).font(.subheadline.bold()); if row["laboratory_at"] != nil { Image(systemName: "checkmark.seal.fill").foregroundStyle(.green).font(.caption) }
                Spacer()
                Text("\(display(number(row, "litres"))) / \(tank.capacityLitres.map { display(Decimal($0)) } ?? "—") l").font(.caption.bold())
                if number(row, "litres") == 0 { Text("pusty").font(.caption.bold()).foregroundStyle(.teal) }
            }
            Text(tank.oil ? "Rodzaj oleju: \(text(row, "oil_type", "—"))" : (text(row, "material").isEmpty ? tank.description : text(row, "material"))).font(.caption.weight(.medium)).fixedSize(horizontal: false, vertical: true)
            if tank.oil {
                HStack { Text("Ważność: \(batch.expires.isEmpty ? "—" : batch.expires)"); Spacer(); Text("\(text(row, "temperature", "—")) °C") }.font(.caption.weight(.semibold))
                if !batch.notes.isEmpty { Text(batch.notes).font(.caption).fixedSize(horizontal: false, vertical: true) }
                HStack { if row["filled_at"] != nil { Text("Napełniono: \(Date(timeIntervalSince1970: Double(integer(row, "filled_at")) / 1000).formatted(date: .abbreviated, time: .shortened))").font(.system(size: 10)) }; Spacer(); Button("Zeruj") { reset = true }.font(.system(size: 10, weight: .semibold)).buttonStyle(.bordered).controlSize(.mini) }
            } else {
                HStack(spacing: 10) {
                    Text(tank.butter ? "Tłuszcz \(text(row, "fat_percent", "—"))%" : "Brix \(text(row, "brix", "—"))")
                    Text("pH \(text(row, "ph", "—"))"); Text("SH \(text(row, "sh", "—"))"); Text("\(text(row, "temperature", "—")) °C")
                    if tank.powder { Text("\(text(row, "evaporator", "—"))/\(row["crystallizer"].map { "\($0)" } ?? "—")") }
                }.font(.system(size: 10, weight: .semibold)).fixedSize(horizontal: false, vertical: true)
            }
        }.padding(10).frame(maxWidth: .infinity, alignment: .leading)
            .background(alignment: .leading) {
                GeometryReader { proxy in let fraction = tank.capacityLitres.map { min(1, max(0, NSDecimalNumber(decimal: (number(row, "litres") ?? 0) / Decimal($0)).doubleValue)) } ?? 0
                    Rectangle().fill(tone.opacity(tank.oil && batch.urgency(litres: number(row, "litres"), now: Date()) == 2 ? 0.24 : 0.08)).frame(width: proxy.size.width * fraction)
                }
            }.background(tone.opacity(0.025)).clipShape(RoundedRectangle(cornerRadius: 12)).overlay(RoundedRectangle(cornerRadius: 12).stroke(tone.opacity(0.25)))
            .confirmationDialog("Wyzerować \(tank.name)? Historia zostanie zachowana.", isPresented: $reset, titleVisibility: .visible) {
                Button("Zeruj zbiornik", role: .destructive) {
                    let old = store.document(.inventory).row("tank_states", tank.id) ?? ["tank_id": tank.id, "material": "", "oil_type": ""]; let id = UUID().uuidString.lowercased()
                    store.run { try await store.mutate(.inventory) { doc in try Operations.editTank(&doc, tank: tank, expected: old, fields: ["material": "", "oil_type": ""], cubicMetres: "0", batch: tank.oil ? OilBatch() : nil, laboratory: false, role: store.role, requestID: id, now: Date()) } }
                }
            }
    }
}
struct TankEditor: View {
    @EnvironmentObject var store: CloudStore; @Environment(\.dismiss) var dismiss
    let tank: Tank; let laboratory: Bool
    @State private var expected: Row = [:]; @State private var quantity = ""; @State private var fields: Row = [:]
    @State private var batch = OilBatch(); @State private var expiry = Date(); @State private var hasExpiry = false
    @State private var transfer = false
    var body: some View {
        NavigationStack { Form {
            Section("Stan zbiornika") {
                TextField("Ilość w m³", text: $quantity).keyboardType(.decimalPad)
                Text("Pojemność: \(tank.capacityLitres.map { display(Decimal($0)) } ?? "—") l").font(.caption)
                if tank.oil {
                    field("Rodzaj oleju", "oil_type")
                    Toggle("Data ważności", isOn: $hasExpiry)
                    if hasExpiry { DatePicker("Ważność", selection: $expiry, displayedComponents: .date) }
                    field("Temperatura [°C]", "temperature", numeric: true)
                    TextField("Uwagi", text: $batch.notes, axis: .vertical).lineLimit(2...5)
                } else {
                    field("Zawartość", "material")
                    field(tank.butter ? "Tłuszcz [%]" : "Brix", tank.butter ? "fat_percent" : "brix", numeric: true)
                    field("pH", "ph", numeric: true); field("SH", "sh", numeric: true); field("Temperatura [°C]", "temperature", numeric: true)
                }
                if expected["filled_at"] != nil { Text("Napełniono: \(Date(timeIntervalSince1970: Double(integer(expected, "filled_at")) / 1000).formatted(date: .abbreviated, time: .shortened))").font(.caption) }
            }
            if tank.powder {
                Section("Kierunek produkcji") {
                    Picker("Wyparka", selection: Binding(get: { text(fields, "evaporator") }, set: { fields["evaporator"] = $0.isEmpty ? nil : $0 })) { Text("—").tag(""); Text("C").tag("C"); Text("F").tag("F") }
                    Picker("Krystalizator", selection: Binding(get: { Int(integer(fields, "crystallizer")) }, set: { fields["crystallizer"] = $0 == 0 ? nil : $0 })) { Text("—").tag(0); ForEach(1...6, id: \.self) { Text("\($0)").tag($0) } }
                    Button("Zeruj kierunek") { fields.removeValue(forKey: "evaporator"); fields.removeValue(forKey: "crystallizer") }
                }
            }
            Section { Button("Dolej / przemieść materiał") { transfer = true }; NavigationLink("Historia napełnień i przemieszczeń") { TankHistory(tank: tank) } }
            if laboratory && store.role != "laboratory" { Text("Zapis pomiarów Laboratorium wymaga uprawnienia laboratory.").foregroundStyle(.orange) }
        }.navigationTitle(tank.name).toolbar {
            ToolbarItem(placement: .cancellationAction) { Button("Anuluj") { dismiss() }.disabled(store.busy) }
            ToolbarItem(placement: .confirmationAction) { Button("Zapisz") { save() }.disabled(store.busy || (laboratory && store.role != "laboratory")) }
        }.onAppear {
            expected = store.document(.inventory).row("tank_states", tank.id) ?? ["tank_id": tank.id, "material": "", "oil_type": ""]
            fields = expected; quantity = number(expected, "litres").map { string($0 / 1000) } ?? ""
            batch = Operations.oilBatch(store.document(.inventory), tankID: tank.id); hasExpiry = !batch.expires.isEmpty; expiry = ProductionClock.date(batch.expires) ?? Date()
        }.sheet(isPresented: $transfer) { MaterialEditor(target: tank) }
        }
    }
    func field(_ title: String, _ key: String, numeric: Bool = false) -> some View {
        TextField(title, text: Binding(get: { text(fields, key) }, set: { fields[key] = $0.isEmpty ? nil : $0.replacingOccurrences(of: ",", with: ".") })).keyboardType(numeric ? .numbersAndPunctuation : .default)
    }
    func save() {
        let id = UUID().uuidString.lowercased(); var savedBatch = batch; savedBatch.expires = hasExpiry ? ProductionClock.day(expiry) : ""; let savedFields = fields; let old = expected; let qty = quantity
        store.run { try await store.mutate(.inventory) { doc in try Operations.editTank(&doc, tank: tank, expected: old, fields: savedFields, cubicMetres: qty, batch: tank.oil ? savedBatch : nil, laboratory: laboratory, role: store.role, requestID: id, now: Date()) }; dismiss() }
    }
}
struct MaterialEditor: View {
    @EnvironmentObject var store: CloudStore; @Environment(\.dismiss) var dismiss
    let target: Tank
    @State private var source = ""; @State private var external = ""; @State private var quantity = ""; @State private var note = ""
    var body: some View { NavigationStack { Form {
        Picker("Skąd", selection: $source) { Text("Dostawa z zewnątrz").tag(""); ForEach(Catalog.tanks.filter { $0.id != target.id }) { Text($0.name).tag($0.id) } }
        if source.isEmpty { TextField("Skąd pochodzi materiał", text: $external) }
        Text("Dokąd: \(target.name)"); TextField("Ilość w m³", text: $quantity).keyboardType(.decimalPad); TextField("Uwagi", text: $note, axis: .vertical)
    }.navigationTitle("Dolewanie / transfer").toolbar {
        ToolbarItem(placement: .cancellationAction) { Button("Anuluj") { dismiss() } }
        ToolbarItem(placement: .confirmationAction) { Button("Zapisz") {
            let id = UUID().uuidString.lowercased(); let origin = Catalog.tanks.first { $0.id == source }
            store.run { try await store.mutate(.inventory) { doc in try Operations.addMaterial(&doc, target: target, source: origin, cubicMetres: quantity, external: external, note: note, requestID: id, now: Date()) }; dismiss() }
        }.disabled(store.busy) }
    } } }
}
struct TankHistory: View {
    @EnvironmentObject var store: CloudStore; let tank: Tank
    var body: some View { List { ForEach(store.rows("movements", .inventory).filter { text($0, "target_id") == tank.id || text($0, "source_id") == tank.id }.reversed().map(Record.init)) { record in
        VStack(alignment: .leading, spacing: 5) {
            Text("\(text(record.row, "source_id", text(record.row, "external_source", "Ręczna edycja"))) → \(text(record.row, "target_id"))").font(.subheadline.bold())
            Text("\(display(number(record.row, "litres"))) l · \(text(record.row, "type"))").font(.caption)
            Text(Date(timeIntervalSince1970: Double(integer(record.row, "occurred_at")) / 1000), format: .dateTime.day().month().year().hour().minute()).font(.caption).foregroundStyle(.secondary)
            let note = text(record.row, "note").components(separatedBy: "\n[milkyway-oil-").first ?? ""
            if !note.isEmpty { Text(note).font(.caption) }
        }
    } }.navigationTitle("Historia · \(tank.name)") }
}
struct EstimatesView: View {
    @EnvironmentObject var store: CloudStore
    @State private var department = "Proszkownia"; @AppStorage("estimate-density") private var density = "1"
    @AppStorage("estimate-powder-content") private var powder = "96"; @AppStorage("estimate-butter-content") private var butter = "82"
    @AppStorage("estimate-recovery") private var recovery = "100"
    var body: some View { List {
        Section("Założenia przybliżenia") {
            Picker("Dział", selection: $department) { Text("Proszkownia").tag("Proszkownia"); Text("Masłownia").tag("Masłownia") }.pickerStyle(.segmented)
            TextField("Gęstość [kg/l]", text: $density).keyboardType(.decimalPad)
            TextField("Sucha masa proszku [%]", text: $powder).keyboardType(.decimalPad)
            TextField("Tłuszcz w maśle [%]", text: $butter).keyboardType(.decimalPad)
            TextField("Uzysk [%]", text: $recovery).keyboardType(.decimalPad)
        }
        Section("Przewidywana produkcja") { ForEach(Catalog.tanks.filter { $0.group.hasPrefix(department) }) { tank in
            let row = store.document(.inventory).row("tank_states", tank.id) ?? [:]
            HStack { Text(tank.name).fontWeight(.semibold); Spacer(); Text(estimate(tank, row)).font(.caption) }
        } }
        Text("Wartości przybliżone: litry × gęstość × udział składnika / udział w produkcie × uzysk. Bez korekty z pH i temperatury.").font(.caption)
    }.navigationTitle("Przewidywanie produkcji") }
    func estimate(_ tank: Tank, _ row: Row) -> String {
        guard let l = number(row, "litres"), let content = number(row, tank.butter ? "fat_percent" : "brix"), let d = decimal(density), d > 0, let target = decimal(tank.butter ? butter : powder), target > 0, target <= 100, let r = decimal(recovery), r >= 0, r <= 100 else { return "Uzupełnij parametry" }
        return "≈ \(display(l * d * content * r / (target * 100))) kg"
    }
}
