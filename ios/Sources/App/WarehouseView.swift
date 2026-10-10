import SwiftUI

struct WarehouseView: View {
    @EnvironmentObject var store: CloudStore
    @State private var day = ProductionClock.date(ProductionClock.warehouseDay(Date()))!
    @State private var adding = false; @State private var removing: WarehouseSelection?; @State private var detail: ProductSelection?
    var body: some View {
        VStack(spacing: 8) {
            DateBar(day: $day, current: ProductionClock.date(ProductionClock.warehouseDay(Date()))!).padding(.horizontal)
            Text("Dzień produkcyjny 09:00–09:00 następnego dnia").font(.caption).foregroundStyle(.secondary)
            ScrollView {
                LazyVStack(spacing: 6) {
                    let receipts = store.rows("production_completions", .production).filter { text($0, "produced_on") == ProductionClock.day(day) && $0["warehouse_removed_at"] == nil }
                    let groups = Dictionary(grouping: receipts, by: { text($0, "entry_id") })
                    ForEach(groups.keys.sorted(), id: \.self) { id in
                        if let row = store.document(.production).row("production_queue", id) {
                            let rows = groups[id]!; let total = rows.reduce(Decimal(0)) { $0 + (number($1, "amount") ?? 0) }
                            let produced = number(row, "produced_amount") ?? 0; let planned = number(row, "planned_amount") ?? 0
                            let color: Color = produced > planned ? .blue : (produced < planned ? .yellow : .green)
                            Group {
                                HStack {
                                    VStack(alignment: .leading, spacing: 3) {
                                        Text(text(row, "title")).font(.system(size: 13, weight: .bold))
                                        Text("\(Line(rawValue: text(row, "line"))?.title ?? "") · \(display(total)) \(text(row, "unit") == "LITRES" ? "l" : "kg")").font(.caption)
                                        Text(produced > planned ? "Zamówiono \(display(planned)) + nadmiar \(display(produced - planned))" : (produced < planned ? "Pozostało \(display(planned - produced))" : "Wyprodukowano w całości")).font(.system(size: 10))
                                    }.frame(maxWidth: .infinity, alignment: .leading)
                                    Button { removing = WarehouseSelection(id: id, day: ProductionClock.day(day), receipts: Set(rows.map { text($0, "id") }), reject: false) } label: { Image(systemName: "trash").font(.caption).padding(8) }.accessibilityLabel("Usuń wpis magazynu")
                                }.padding(9).background(color.opacity(0.12), in: RoundedRectangle(cornerRadius: 10)).overlay(RoundedRectangle(cornerRadius: 10).stroke(color.opacity(0.3)))
                            }.contentShape(Rectangle()).onTapGesture { detail = ProductSelection(row: row) }
                        }
                    }
                    let rejects = store.rows("production_rejects", .production).filter { text($0, "record_date") == ProductionClock.day(day) }
                    ForEach(rejects.map(Record.init)) { record in
                        HStack {
                            VStack(alignment: .leading) { Text("Wybrakowano: \(text(record.row, "description"))").font(.caption.bold()); Text("\(display(number(record.row, "kilograms"))) kg · \(Line(rawValue: text(record.row, "line"))?.title ?? "")").font(.caption) }.frame(maxWidth: .infinity, alignment: .leading)
                            Button { removing = WarehouseSelection(id: record.id, day: ProductionClock.day(day), receipts: [], reject: true) } label: { Image(systemName: "trash").font(.caption).padding(8) }
                        }.padding(9).background(.red.opacity(0.06), in: RoundedRectangle(cornerRadius: 10))
                    }
                    if !store.ready.contains(.production) { ProgressView("Ładowanie…") }
                    else if receipts.isEmpty && rejects.isEmpty { Text("Brak produkcji z tego dnia").foregroundStyle(.secondary).padding(40) }
                }.padding(.horizontal).padding(.bottom)
            }.background(noteColor(ProductionClock.day(day)).opacity(0.035))
                .simultaneousGesture(DragGesture(minimumDistance: 40).onEnded { value in
                    if abs(value.translation.width) > abs(value.translation.height) { day = ProductionClock.calendar.date(byAdding: .day, value: value.translation.width < 0 ? 1 : -1, to: day)! }
                })
        }.navigationTitle("Magazyn/wyprodukowano").navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .topBarTrailing) { Button("Wybrakowany towar", systemImage: "plus") { adding = true }.disabled(!store.ready.contains(.production)) } }
            .sheet(isPresented: $adding) { RejectEditor(day: ProductionClock.day(day)) }
            .sheet(item: $detail) { ProductDetail(entry: $0.row) }
            .sheet(item: $removing) { selected in PINSheet(title: "Usuń wpis magazynu") {
                let id = UUID().uuidString.lowercased()
                store.run { try await store.mutate(.production) { doc in
                    if selected.reject { try require(doc.row("production_rejects", selected.id) != nil, "Wpis został już usunięty."); doc.remove("production_rejects", id: selected.id) }
                    else { try Operations.removeWarehouse(&doc, entryID: selected.id, day: selected.day, receiptIDs: selected.receipts, requestID: id, pin: "5522", now: Date()) }
                } }
            } }
    }
}
struct WarehouseSelection: Identifiable { let id: String; let day: String; let receipts: Set<String>; let reject: Bool }
struct RejectEditor: View {
    @EnvironmentObject var store: CloudStore; @Environment(\.dismiss) var dismiss
    let day: String
    @State private var line = Line.butter; @State private var description = ""; @State private var quantity = ""
    var body: some View { NavigationStack { Form {
        Picker("Dział", selection: $line) { ForEach(Line.allCases) { Text($0.title).tag($0) } }
        TextField("Opis wybrakowanego towaru", text: $description, axis: .vertical); TextField("Masa [kg]", text: $quantity).keyboardType(.decimalPad)
    }.navigationTitle("Wybrakowany towar").toolbar {
        ToolbarItem(placement: .cancellationAction) { Button("Anuluj") { dismiss() } }
        ToolbarItem(placement: .confirmationAction) { Button("Zapisz") {
            let id = UUID().uuidString.lowercased(); let now = Date()
            store.run { try await store.mutate(.production) { doc in
                let qty = try amount(quantity); try require(!description.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty && description.count <= 2000, "Wpisz opis (do 2000 znaków).")
                if doc.row("production_rejects", id) == nil { doc.put("production_rejects", id: id, row: ["id": id, "line": line.rawValue, "record_date": day, "description": description, "kilograms": string(qty), "created_at": Operations.timestamp(now)]) }
            }; dismiss() }
        }.disabled(store.busy || description.isEmpty) }
    } } }
}
