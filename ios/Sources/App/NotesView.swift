import SwiftUI
import UserNotifications

struct ReminderItem: Identifiable {
    let id: String; let domain: Domain; let table: String; let row: Row; let annotation: Annotation
}
enum LocalReminders {
    @MainActor static func items(_ store: CloudStore) -> [ReminderItem] {
        store.rows("work_notes", .notes).compactMap { row in
            var note = Annotation(text(row, "body")); if integer(row, "completed") == 1 { note.reminder?.done = true }
            return note.reminder == nil ? nil : ReminderItem(id: "work:\(text(row, "id"))", domain: .notes, table: "work_notes", row: row, annotation: note)
        } + store.rows("production_product_notes", .production).compactMap { row in
            let note = Annotation(text(row, "note")); return note.reminder == nil ? nil : ReminderItem(id: "product:\(text(row, "id"))", domain: .production, table: "production_product_notes", row: row, annotation: note)
        }
    }
    static func clear() { UNUserNotificationCenter.current().removeAllPendingNotificationRequests(); UNUserNotificationCenter.current().removeAllDeliveredNotifications() }
    @MainActor static func sync(store: CloudStore) {
        let items = items(store); let account = store.account
        Task {
            let center = UNUserNotificationCenter.current(); center.removeAllPendingNotificationRequests()
            let settings = await center.notificationSettings(); guard settings.authorizationStatus == .authorized || settings.authorizationStatus == .provisional else { return }
            let future = items.filter { item in guard let r = item.annotation.reminder else { return false }; return !r.done && !r.readers.contains(account) && r.at > Operations.timestamp(Date()) }
            for item in future.sorted(by: { $0.annotation.reminder!.at < $1.annotation.reminder!.at }).prefix(60) {
                let content = UNMutableNotificationContent(); content.title = "Milkyway · przypomnienie"; content.body = item.annotation.body; content.sound = .default
                let seconds = max(1, Double(item.annotation.reminder!.at) / 1000 - Date().timeIntervalSince1970)
                try? await center.add(UNNotificationRequest(identifier: item.id, content: content, trigger: UNTimeIntervalNotificationTrigger(timeInterval: seconds, repeats: false)))
            }
        }
    }
}
struct ReminderCard: View {
    @EnvironmentObject var store: CloudStore
    let item: ReminderItem; let now: Date
    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text(item.annotation.body).font(.subheadline.weight(.medium)).foregroundStyle(item.annotation.important ? .red : .primary)
            HStack { action("Zrobione", "done", .green); Spacer(); action("OK", "read", .indigo); Spacer(); action("Pilne", "urgent", .red) }
        }.padding(14).background(.orange.opacity(Int(now.timeIntervalSince1970) % 2 == 0 ? 0.16 : 0.08), in: RoundedRectangle(cornerRadius: 16)).overlay(RoundedRectangle(cornerRadius: 16).stroke(.orange.opacity(0.3)))
            .animation(.easeInOut(duration: 0.7), value: Int(now.timeIntervalSince1970) % 2)
    }
    func action(_ title: String, _ action: String, _ color: Color) -> some View {
        Button(title) { store.run { try await store.mutate(item.domain) { doc in try Operations.reminderAction(&doc, table: item.table, id: text(item.row, "id"), token: item.annotation.reminder!.token, action: action, account: store.account, now: Date()) } } }.font(.caption.bold()).tint(color).buttonStyle(.bordered)
    }
}
func noteColor(_ id: String) -> Color {
    let colors: [Color] = [.blue, .teal, .purple, .orange, .pink, .indigo, .mint, .yellow]
    return colors[Int(id.utf8.reduce(UInt32(0)) { ($0 &* 31) &+ UInt32($1) }) % colors.count]
}
struct NotesView: View {
    @EnvironmentObject var store: CloudStore
    let scope: Int; let kind: String; let title: String; var board = false
    @State private var day = Date(); @State private var editing: NoteSelection?; @State private var delete: NoteSelection?
    var rows: [Row] { store.rows("work_notes", .notes).filter { integer($0, "scope") == scope && text($0, "kind") == kind && (!board || ProductionClock.day(Date(timeIntervalSince1970: Double(integer($0, "created_at")) / 1000)) == ProductionClock.day(day)) }.sorted { integer($0, "updated_at") > integer($1, "updated_at") } }
    var body: some View {
        VStack(spacing: 8) {
            if board { DateBar(day: $day).padding(.horizontal) }
            ScrollView { LazyVStack(spacing: 10) {
                if !store.ready.contains(.notes) { ProgressView("Ładowanie…") }
                else if rows.isEmpty { Text("Brak wpisów").foregroundStyle(.secondary).padding(40) }
                ForEach(rows.map(Record.init)) { record in
                    let row = record.row; let note = Annotation(text(row, "body"))
                    Group {
                        VStack(alignment: .leading, spacing: 8) {
                            HStack {
                                Text(Date(timeIntervalSince1970: Double(integer(row, "created_at")) / 1000), format: .dateTime.day().month().year().hour().minute()).font(.caption).foregroundStyle(.secondary)
                                Spacer()
                                if integer(row, "completed") == 1 { Image(systemName: "checkmark.circle.fill").foregroundStyle(.green) }
                                if board { Button { delete = NoteSelection(id: record.id, row: row) } label: { Image(systemName: "xmark").font(.system(size: 10, weight: .bold)).padding(8) }.accessibilityLabel("Usuń ogłoszenie") }
                            }
                            Text(note.body).font(.subheadline.weight(.medium)).foregroundStyle(note.important ? .red : .primary).frame(maxWidth: .infinity, alignment: .leading)
                            if !note.author.isEmpty { Text(note.author).font(.caption).foregroundStyle(.secondary) }
                            if let reminder = note.reminder { Label(Date(timeIntervalSince1970: Double(reminder.at) / 1000).formatted(date: .abbreviated, time: .shortened), systemImage: "clock").font(.caption).foregroundStyle(.orange) }
                        }.padding(12).background(noteColor(record.id).opacity(0.08), in: RoundedRectangle(cornerRadius: 16)).overlay(RoundedRectangle(cornerRadius: 16).stroke(noteColor(record.id).opacity(0.2)))
                    }.contentShape(Rectangle()).onTapGesture { editing = NoteSelection(id: record.id, row: row) }
                }
            } }.simultaneousGesture(DragGesture(minimumDistance: 40).onEnded { value in
                if board && abs(value.translation.width) > abs(value.translation.height) { day = ProductionClock.calendar.date(byAdding: .day, value: value.translation.width < 0 ? 1 : -1, to: day)! }
            })
        }.navigationTitle(title)
            .toolbar { ToolbarItem(placement: .topBarTrailing) { Button("Dodaj", systemImage: "plus") { editing = NoteSelection(id: UUID().uuidString.lowercased(), row: nil) }.disabled(!store.ready.contains(.notes)) } }
            .sheet(item: $editing) { item in NoteEditor(selection: item, scope: scope, kind: kind) }
            .confirmationDialog("Usunąć ogłoszenie?", isPresented: Binding(get: { delete != nil }, set: { if !$0 { delete = nil } }), titleVisibility: .visible) {
                Button("Usuń", role: .destructive) {
                    guard let selected = delete, let row = selected.row else { return }; delete = nil
                    store.run { try await store.mutate(.notes) { doc in _ = try Operations.current(doc, table: "work_notes", expected: row); doc.remove("work_notes", id: selected.id) } }
                }
                Button("Anuluj", role: .cancel) { delete = nil }
            }
    }
}
struct NoteSelection: Identifiable { let id: String; let row: Row? }
struct Record: Identifiable { let row: Row; init(_ row: Row) { self.row = row }; var id: String { text(row, "id") } }
struct NoteEditor: View {
    @EnvironmentObject var store: CloudStore; @Environment(\.dismiss) var dismiss
    let selection: NoteSelection; var scope = 0; var kind = "CURRENT_NOTES"; var product: Row? = nil
    @State private var note = Annotation(); @State private var time = Date().addingTimeInterval(3600)
    @State private var clock = false
    var body: some View {
        NavigationStack {
            VStack(spacing: 12) {
                TextEditor(text: $note.body).font(.subheadline).scrollContentBackground(.hidden).padding(8)
                    .background(.background, in: RoundedRectangle(cornerRadius: 16)).overlay(RoundedRectangle(cornerRadius: 16).stroke(.gray.opacity(0.25))).padding(.horizontal)
                if !note.author.isEmpty { Text(note.author).font(.caption).foregroundStyle(.secondary) }
                if let r = note.reminder { Text(Date(timeIntervalSince1970: Double(r.at) / 1000), format: .dateTime.day().month().year().hour().minute()).font(.caption).foregroundStyle(.orange) }
                HStack {
                    marker("Ważne", "exclamationmark.circle", .red, note.important) { note.important.toggle() }
                    marker("Przypomnienie", "clock", .orange, note.reminder != nil) { clock = true }
                    marker("Załatwione", "checkmark.circle", .green, note.completed) { note.completed.toggle(); note.reminder?.done = note.completed }
                }.padding(12).background(.thinMaterial)
            }.padding(.top).navigationTitle("")
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) { Button("Anuluj") { dismiss() }.disabled(store.busy) }
                    ToolbarItem(placement: .confirmationAction) { Button("Zapisz") { save() }.disabled(store.busy || note.body.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty) }
                }
                .onAppear {
                    note = Annotation(text(selection.row ?? [:], product == nil ? "body" : "note"))
                    if selection.row == nil { note.author = store.name } else if product == nil { note.completed = integer(selection.row!, "completed") == 1 }
                    if let r = note.reminder { time = Date(timeIntervalSince1970: Double(r.at) / 1000) }
                }
                .sheet(isPresented: $clock) {
                    NavigationStack { VStack {
                        DatePicker("Data", selection: $time, in: Date()..., displayedComponents: .date).datePickerStyle(.graphical)
                        DatePicker("Godzina", selection: $time, displayedComponents: .hourAndMinute).datePickerStyle(.wheel).labelsHidden()
                        Button("Ustaw przypomnienie") {
                            note.reminder = Reminder(at: Operations.timestamp(time)); note.completed = false; clock = false
                            Task { _ = try? await UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound, .badge]) }
                        }.buttonStyle(.borderedProminent)
                        if note.reminder != nil { Button("Usuń przypomnienie", role: .destructive) { note.reminder = nil; clock = false } }
                    }.padding().navigationTitle("Przypomnienie").toolbar { Button("Anuluj") { clock = false } } }
        }
    }
    }
    func marker(_ title: String, _ symbol: String, _ color: Color, _ enabled: Bool, _ action: @escaping () -> Void) -> some View {
        Button(action: action) { VStack(spacing: 4) { Image(systemName: symbol); Text(title).font(.system(size: 11, weight: .semibold)) }.frame(maxWidth: .infinity).padding(.vertical, 8).foregroundStyle(color).background(color.opacity(enabled ? 0.27 : 0.10), in: RoundedRectangle(cornerRadius: 10)).overlay(RoundedRectangle(cornerRadius: 10).stroke(color.opacity(enabled ? 0.8 : 0.25))) }
    }
    func save() {
        let saved = note; let now = Date()
        store.run {
            if let product {
                try await store.mutate(.production) { doc in
                    let current = try Operations.current(doc, table: "production_queue", expected: product)
                    try require(saved.body.count <= 4000, "Notatka może mieć do 4000 znaków.")
                    if let r = saved.reminder { try require(r.at > Operations.timestamp(now), "Wybierz przyszłą godzinę.") }
                    if doc.row("production_product_notes", selection.id) == nil { doc.put("production_product_notes", id: selection.id, row: ["id": selection.id, "entry_id": text(current, "id"), "stage": integer(current, "pending_order") == 1 ? "ORDER" : (Operations.active(current) ? "PRODUCTION" : "COMPLETED"), "note": saved.encoded, "created_at": Operations.timestamp(now)]) }
                }
            } else { try await store.mutate(.notes) { doc in try Operations.saveNote(&doc, expected: selection.row, id: selection.id, scope: scope, kind: kind, annotation: saved, now: now) } }
            dismiss()
        }
    }
}
