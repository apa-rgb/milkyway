import SwiftUI

enum Line: String, CaseIterable, Identifiable {
    case butter = "BUTTER", powder = "POWDER", uht = "UHT"
    var id: String { rawValue }
    var title: String { switch self { case .butter: return "Masłownia"; case .powder: return "Proszkownia"; case .uht: return "UHT" } }
}
struct ProductionMenu: View {
    var body: some View { List { NavigationLink("Kolejka produkcji") { List { ForEach(Line.allCases) { line in NavigationLink(line.title) { PlanningView(line: line) } } }.navigationTitle("Kolejka produkcji") } }.navigationTitle("Produkcja") }
}
struct PlanningView: View {
    @EnvironmentObject var store: CloudStore
    let line: Line
    @State private var day = Date(); @State private var visibleDay: String?; @State private var adding = false
    @State private var action: ProductSelection?; @State private var windowStart = ProductionClock.calendar.startOfDay(for: Date())
    var entries: [Row] { store.rows("production_queue", .production).filter { text($0, "line") == line.rawValue && Operations.active($0) }.sorted { integer($0, "position") < integer($1, "position") } }
    var days: [String] { (-14...60).map { ProductionClock.day(ProductionClock.calendar.date(byAdding: .day, value: $0, to: windowStart)!) } }
    var body: some View {
        VStack(spacing: 8) {
            DateBar(day: Binding(get: { day }, set: { d in day = d; if !days.contains(ProductionClock.day(d)) { windowStart = ProductionClock.calendar.startOfDay(for: d) }; visibleDay = ProductionClock.day(d) })).padding(.horizontal)
            HStack(alignment: .top, spacing: 8) {
                VStack(spacing: 6) {
                    Text("Produkcja").font(.subheadline.bold())
                    ScrollView {
                        LazyVStack(spacing: 10) { ForEach(days, id: \.self) { date in
                            dayPlan(date).id(date)
                        } }.scrollTargetLayout()
                    }.scrollPosition(id: $visibleDay, anchor: .top)
                        .onChange(of: visibleDay) { _, new in if let new, let d = ProductionClock.date(new) { day = d } }
                }
                VStack(spacing: 6) {
                    Text("Wpłynęło zamówienie").font(.system(size: 12, weight: .bold)).fixedSize(horizontal: false, vertical: true)
                    ScrollView { LazyVStack(spacing: 7) { ForEach(entries.filter { integer($0, "pending_order") == 1 }.map(Record.init)) { record in card(record.row) } }.padding(5) }
                        .background(.gray.opacity(0.035), in: RoundedRectangle(cornerRadius: 12))
                        .dropDestination(for: String.self) { ids, _ in
                            guard let id = ids.first, let row = entries.first(where: { text($0, "id") == id }) else { return false }
                            store.run { try await store.mutate(.production) { doc in try Operations.returnPending(&doc, expected: row, now: Date()) } }; return true
                        }
                }
            }.padding(.horizontal, 8)
            if !store.ready.contains(.production) { ProgressView("Ładowanie…") }
        }.navigationTitle(line.title).navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .topBarTrailing) { Button("Dodaj zamówienie", systemImage: "plus") { adding = true }.disabled(!store.ready.contains(.production)) } }
            .sheet(isPresented: $adding) { OrderEditor(line: line) }
            .sheet(item: $action) { ProductDetail(entry: $0.row) }
            .onAppear { visibleDay = ProductionClock.day(day) }
    }
    func dayPlan(_ date: String) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(date).font(.caption.bold()).padding(.vertical, 5)
            let scheduled = entries.filter { integer($0, "pending_order") == 0 && text($0, "plan_date") == date }
            ForEach(scheduled.filter { text($0, "planned_time").isEmpty }.map(Record.init)) { record in card(record.row) }
            ForEach(stride(from: 0, through: 22, by: 2).map { $0 }, id: \.self) { hour in
                VStack(alignment: .leading, spacing: 5) {
                    Text(String(format: "%02d:00", hour)).font(.system(size: 10, weight: .semibold)).foregroundStyle(.secondary)
                    ForEach(scheduled.filter { (Int(text($0, "planned_time").prefix(2)) ?? -100) / 2 == hour / 2 }.map(Record.init)) { record in card(record.row) }
                    if !scheduled.contains(where: { (Int(text($0, "planned_time").prefix(2)) ?? -100) / 2 == hour / 2 }) { RoundedRectangle(cornerRadius: 8).stroke(.gray.opacity(0.13), style: StrokeStyle(lineWidth: 1, dash: [3])).frame(height: 26) }
                }.padding(5).contentShape(Rectangle()).dropDestination(for: String.self) { ids, _ in
                    guard let id = ids.first, let row = entries.first(where: { text($0, "id") == id }) else { return false }
                    let codeID = "automatic-production-code:" + UUID().uuidString.lowercased()
                    store.run { try await store.mutate(.production) { doc in try Operations.schedule(&doc, expected: row, day: date, time: String(format: "%02d:00", hour), now: Date(), codeID: codeID) } }; return true
                }
            }
        }.padding(7).frame(maxWidth: .infinity).background(noteColor(date).opacity(0.045), in: RoundedRectangle(cornerRadius: 12))
    }
    func card(_ row: Row) -> some View {
        Button { action = ProductSelection(row: row) } label: { ProductCard(entry: row) }.buttonStyle(.plain).draggable(text(row, "id"))
            .contextMenu {
                Button("Otwórz / wyprodukowano", systemImage: "checkmark.circle") { action = ProductSelection(row: row) }
                Button("W górę", systemImage: "arrow.up") { move(row, -1) }; Button("W dół", systemImage: "arrow.down") { move(row, 1) }
                if integer(row, "pending_order") == 0 { Button("Do oczekujących", systemImage: "arrow.uturn.right") { store.run { try await store.mutate(.production) { doc in try Operations.returnPending(&doc, expected: row, now: Date()) } } } }
            }
    }
    func move(_ row: Row, _ direction: Int) { store.run { try await store.mutate(.production) { doc in try Operations.move(&doc, expected: row, direction: direction, now: Date()) } } }
}
struct ProductSelection: Identifiable { let row: Row; var id: String { text(row, "id") } }
struct ProductCard: View {
    @EnvironmentObject var store: CloudStore
    let entry: Row
    var note: String { store.rows("production_product_notes", .production).reversed().first { text($0, "entry_id") == text(entry, "id") && !Operations.isCode(text($0, "note")) }.map { Annotation(text($0, "note")).body } ?? text(entry, "description").components(separatedBy: "\n").drop(while: { $0.hasPrefix("Rodzaj produktu: ") }).joined(separator: "\n") }
    var color: Color { text(entry, "line") == "BUTTER" ? (text(entry, "description").hasPrefix("Rodzaj produktu: Mix") ? .orange : .yellow) : noteColor(text(entry, "id")) }
    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(text(entry, "title")).font(.system(size: 12, weight: .bold)).lineLimit(2).frame(maxWidth: .infinity, alignment: .leading)
            HStack {
                Text("\(display(Operations.remaining(entry))) \(text(entry, "unit") == "LITRES" ? "l" : "kg")")
                Spacer()
                if text(entry, "line") == "POWDER", let code = Operations.codeNotes(store.document(.production), entryID: text(entry, "id")).first {
                    Text(text(code, "note").replacingOccurrences(of: "Kod produkcji: ", with: "")).font(.system(size: 9, weight: .semibold)).padding(.horizontal, 3).overlay(RoundedRectangle(cornerRadius: 3).stroke(.gray.opacity(0.3)))
                }
                if !text(entry, "planned_time").isEmpty { Text(text(entry, "planned_time")) }
            }.font(.system(size: 10, weight: .semibold))
            if text(entry, "line") == "BUTTER" { Text(text(entry, "description").hasPrefix("Rodzaj produktu: Mix") ? "Mix" : "Masło").font(.system(size: 9, weight: .bold)).padding(.horizontal, 5).overlay(Capsule().stroke(color.opacity(0.7))) }
            Text(note.split(whereSeparator: \.isWhitespace).prefix(2).joined(separator: " ")).font(.system(size: 10)).lineLimit(1)
        }.padding(8).frame(maxWidth: .infinity, alignment: .leading).frame(height: 92, alignment: .top)
            .background(color.opacity(0.11), in: RoundedRectangle(cornerRadius: 10)).overlay(RoundedRectangle(cornerRadius: 10).stroke(color.opacity(0.35)))
    }
}
struct OrderEditor: View {
    @EnvironmentObject var store: CloudStore; @Environment(\.dismiss) var dismiss
    let line: Line
    @State private var title = ""; @State private var quantity = ""; @State private var note = ""; @State private var kind = ""
    var body: some View { NavigationStack { Form {
        TextField("Produkt", text: $title); TextField(line == .uht ? "Ilość [l]" : "Masa [kg]", text: $quantity).keyboardType(.decimalPad)
        if line == .butter { HStack { kindButton("Masło", .yellow); kindButton("Mix", .orange) } }
        TextField("Notatka", text: $note, axis: .vertical).lineLimit(3...6)
    }.navigationTitle("Dodaj zamówienie").toolbar {
        ToolbarItem(placement: .cancellationAction) { Button("Anuluj") { dismiss() } }
        ToolbarItem(placement: .confirmationAction) { Button("Dodaj") {
            let id = UUID().uuidString.lowercased(); let now = Date(); let noteID = UUID().uuidString.lowercased()
            store.run { try await store.mutate(.production) { doc in
                try Operations.addOrder(&doc, id: id, line: line.rawValue, title: title, description: line == .butter ? "Rodzaj produktu: \(kind)\n\(note)" : note, quantity: quantity, now: now)
                if !note.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty && doc.row("production_product_notes", noteID) == nil {
                    var annotation = Annotation(note); annotation.author = store.name
                    doc.put("production_product_notes", id: noteID, row: ["id": noteID, "entry_id": id, "stage": "ORDER", "note": annotation.encoded, "created_at": Operations.timestamp(now)])
                }
            }; dismiss() }
        }.disabled(store.busy || title.isEmpty || (line == .butter && kind.isEmpty)) }
    } } }
    func kindButton(_ value: String, _ color: Color) -> some View { Button { kind = value } label: { Text(value).frame(maxWidth: .infinity).padding(10).background(color.opacity(kind == value ? 0.25 : 0.07), in: RoundedRectangle(cornerRadius: 10)).overlay(RoundedRectangle(cornerRadius: 10).stroke(color.opacity(0.7))) }.buttonStyle(.plain) }
}
struct ProductDetail: View {
    @EnvironmentObject var store: CloudStore; @Environment(\.dismiss) var dismiss
    let entry: Row
    @State private var quantity = ""; @State private var date = Date(); @State private var code = ""
    @State private var note = false; @State private var delete = false
    var current: Row { store.document(.production).row("production_queue", text(entry, "id")) ?? entry }
    var body: some View { NavigationStack { Form {
        Section(text(current, "title")) {
            Text(text(current, "description")); Text("Zamówiono: \(display(number(current, "planned_amount"))) · wyprodukowano: \(display(number(current, "produced_amount"))) · pozostało: \(display(Operations.remaining(current))) \(text(current, "unit") == "LITRES" ? "l" : "kg")").font(.subheadline)
        }
        if Operations.active(current) {
            Section("Termin produkcji") {
                DatePicker("Dzień i godzina", selection: $date)
                Button("Zaplanuj") {
                    let row = current; let f = DateFormatter(); f.timeZone = ProductionClock.calendar.timeZone; f.dateFormat = "HH:mm"
                    let codeID = "automatic-production-code:" + UUID().uuidString.lowercased()
                    store.run { try await store.mutate(.production) { doc in try Operations.schedule(&doc, expected: row, day: ProductionClock.day(date), time: f.string(from: date), now: Date(), codeID: codeID) }; dismiss() }
                }
            }
            if integer(current, "pending_order") == 0 {
                Section("Wyprodukowano") {
                    TextField("Ilość (opcjonalnie)", text: $quantity).keyboardType(.decimalPad)
                    Button(quantity.isEmpty ? "Wyprodukowano w całości" : "Zapisz wyprodukowaną ilość") {
                        let row = current; let id = UUID().uuidString.lowercased()
                        store.run { try await store.mutate(.production) { doc in try Operations.complete(&doc, expected: row, receiptID: id, quantity: quantity.isEmpty ? nil : quantity, now: Date()) }; dismiss() }
                    }
                    Text("Część wyprodukowana trafi do magazynu; pozostała ilość zostanie w kolejce.").font(.caption)
                    Button("Przenieś do oczekujących") { let row = current; store.run { try await store.mutate(.production) { doc in try Operations.returnPending(&doc, expected: row, now: Date()) }; dismiss() } }
                }
            }
        }
        if text(current, "line") == "POWDER" {
            Section("Kod produkcji") {
                TextField("3 cyfry (opcjonalnie)", text: $code).keyboardType(.numberPad).onChange(of: code) { _, v in code = String(v.filter { $0 >= "0" && $0 <= "9" }.prefix(3)) }
                Button("Zapisz kod") {
                    let row = current; let id = UUID().uuidString.lowercased(); let saved = code
                    store.run { try await store.mutate(.production) { doc in
                        _ = try Operations.current(doc, table: "production_queue", expected: row)
                        if doc.row("production_product_notes", id) == nil { doc.put("production_product_notes", id: id, row: ["id": id, "entry_id": text(row, "id"), "stage": "PRODUCTION", "note": "Kod produkcji: \(saved.isEmpty ? "—" : saved)", "created_at": Operations.timestamp(Date())]) }
                    } }
                }.disabled(code.count != 3 && !code.isEmpty)
            }
        }
        Section("Notatki produktu") {
            ForEach(store.rows("production_product_notes", .production).filter { text($0, "entry_id") == text(current, "id") && !Operations.isCode(text($0, "note")) }.reversed().map(Record.init)) { record in
                let annotation = Annotation(text(record.row, "note")); VStack(alignment: .leading) { Text(annotation.body).foregroundStyle(annotation.important ? .red : .primary); Text(annotation.author).font(.caption).foregroundStyle(.secondary) }
            }
            Button("Dodaj notatkę") { note = true }
        }
        if Operations.active(current) { Button("Usuń produkt", role: .destructive) { delete = true } }
    }.disabled(store.busy).navigationTitle("Produkt").toolbar { Button("Zamknij") { dismiss() } }
        .onAppear {
            let time = text(current, "planned_time", "09:00").split(separator: ":")
            let plannedDay = ProductionClock.date(text(current, "plan_date")) ?? Date()
            date = ProductionClock.calendar.date(bySettingHour: Int(time.first ?? "09") ?? 9, minute: time.count > 1 ? Int(time[1]) ?? 0 : 0, second: 0, of: plannedDay) ?? plannedDay
            code = Operations.codeNotes(store.document(.production), entryID: text(current, "id")).first.map { text($0, "note").replacingOccurrences(of: "Kod produkcji: ", with: "").replacingOccurrences(of: "—", with: "") } ?? ""
        }
        .sheet(isPresented: $note) { NoteEditor(selection: NoteSelection(id: UUID().uuidString.lowercased(), row: nil), product: current) }
        .sheet(isPresented: $delete) { let row = current; PINSheet(title: "Usuń produkt") { store.run { try await store.mutate(.production) { doc in try Operations.deleteOrder(&doc, expected: row, pin: "5522") }; dismiss() } } }
    } }
}
