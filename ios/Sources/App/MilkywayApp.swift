import SwiftUI
import UserNotifications

@main struct MilkywayApp: App {
    @StateObject private var store = CloudStore()
    var body: some Scene {
        WindowGroup {
            Group { if store.signedIn { HomeView() } else { LoginView() } }
                .environmentObject(store).tint(.indigo).environment(\.locale, Locale(identifier: "pl_PL"))
                .environment(\.timeZone, ProductionClock.calendar.timeZone)
                .task { await store.start() }
                .alert("Milkyway", isPresented: Binding(get: { store.error != nil }, set: { if !$0 { store.error = nil } })) {
                    Button("OK") { store.error = nil }
                } message: { Text(store.error ?? "") }
        }
    }
}
struct LoginView: View {
    @EnvironmentObject var store: CloudStore
    @State private var account = "01"; @State private var password = ""; @State private var name = ""
    var body: some View {
        NavigationStack {
            Form {
                Section { Image("Cow").resizable().scaledToFit().frame(height: 100).frame(maxWidth: .infinity); Text("Milkyway").font(.largeTitle.bold()); Text("Dziennik produkcji").foregroundStyle(.secondary) }
                if store.config == nil {
                    Section("Konfiguracja") { Text("Projekt iPhone’a wymaga pliku konfiguracji Firebase. Instrukcja znajduje się w ios/README.md.") }
                } else {
                    Section("Logowanie") {
                        Picker("Konto", selection: $account) { ForEach(1...10, id: \.self) { n in let id = String(format: "%02d", n); Text("Operator \(id)").tag(id) } }
                        SecureField("Hasło", text: $password).textContentType(.password)
                        TextField("Twoje imię (opcjonalnie)", text: $name).textContentType(.givenName).onChange(of: name) { _, v in name = String(v.prefix(60)) }
                        Button { Task { await store.login(account: account, password: password, name: name); password = "" } } label: {
                            HStack { Text("Zaloguj"); Spacer(); if store.busy { ProgressView() } }
                        }.disabled(store.busy || password.isEmpty)
                    }
                    Text("Imię będzie widoczne przy dodawanych notatkach.").font(.footnote)
                }
            }
        }
    }
}
struct PINSheet: View {
    let title: String; var expected = "5522"; let approved: () -> Void
    @Environment(\.dismiss) var dismiss
    @State private var pin = ""; @State private var invalid = false
    var body: some View {
        NavigationStack { Form {
            SecureField("PIN", text: $pin).keyboardType(.numberPad)
            if invalid { Text("Nieprawidłowy PIN.").foregroundStyle(.red) }
            Button("Potwierdź") { if pin == expected { dismiss(); approved() } else { invalid = true; pin = "" } }
        }.navigationTitle(title).toolbar { ToolbarItem(placement: .cancellationAction) { Button("Anuluj") { dismiss() } } } }
        .presentationDetents([.height(260)])
    }
}
struct DateBar: View {
    @Binding var day: Date
    var current = Date()
    var body: some View {
        HStack {
            Button { day = ProductionClock.calendar.date(byAdding: .day, value: -1, to: day)! } label: { Image(systemName: "chevron.left") }
            DatePicker("Dzień", selection: $day, displayedComponents: .date).labelsHidden().frame(maxWidth: .infinity)
            Button { day = ProductionClock.calendar.date(byAdding: .day, value: 1, to: day)! } label: { Image(systemName: "chevron.right") }
            Button("Dziś") { day = current }.font(.caption)
        }.padding(10).background(.thinMaterial, in: RoundedRectangle(cornerRadius: 12))
    }
}
struct HomeView: View {
    @EnvironmentObject var store: CloudStore
    @State private var pinShift: Int?; @State private var openedShift: Int?; @State private var now = Date()
    private let timer = Timer.publish(every: 1, on: .main, in: .common).autoconnect()
    var body: some View {
        NavigationStack {
            ScrollView { VStack(spacing: 16) {
                HStack {
                    Image("Cow").resizable().scaledToFit().frame(width: 52, height: 52)
                    VStack(alignment: .leading) { Text("Dziennik produkcji").font(.headline); Text("Milkyway · iOS \(Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "")").font(.caption).foregroundStyle(.secondary) }
                    Spacer()
                }
                HStack { ForEach(1...3, id: \.self) { n in Button("Zmiana \(n)") { pinShift = n }.buttonStyle(.bordered).frame(maxWidth: .infinity) } }
                HStack(spacing: 12) {
                    NavigationLink { NotesView(scope: 0, kind: "CURRENT_NOTES", title: "Tablica ogłoszeń", board: true) } label: { MenuTile(title: "Tablica ogłoszeń", symbol: "pin.fill", color: .orange, height: 162) }
                    VStack(spacing: 12) {
                        NavigationLink { ProductionMenu() } label: { MenuTile(title: "Produkcja", symbol: "gearshape.2.fill", color: .indigo, height: 75) }
                        NavigationLink { WarehouseView() } label: { MenuTile(title: "Magazyn/wyprodukowano", symbol: "shippingbox.fill", color: .green, height: 75) }
                    }
                }
                NavigationLink { DocumentationView() } label: { Label("Dokumentacja produkcji", systemImage: "folder.fill").frame(maxWidth: .infinity, alignment: .leading).padding(14).background(.blue.opacity(0.08), in: RoundedRectangle(cornerRadius: 12)) }
                NavigationLink { OtherMenu() } label: { Label("Inne", systemImage: "square.grid.2x2").frame(maxWidth: .infinity).padding(12).background(.gray.opacity(0.08), in: RoundedRectangle(cornerRadius: 12)) }
                NavigationLink { TanksView() } label: { Label("Zbiorniki", systemImage: "cylinder.split.1x2.fill") }
                ForEach(dueItems, id: \.id) { item in ReminderCard(item: item, now: now) }
                Text("\(store.name.isEmpty ? "Operator \(store.account)" : store.name) · \(store.connection)").font(.caption).foregroundStyle(.secondary)
            }.padding() }
            .navigationDestination(isPresented: Binding(get: { openedShift != nil }, set: { if !$0 { openedShift = nil } })) { ShiftMenu(shift: openedShift ?? 1) }
            .sheet(isPresented: Binding(get: { pinShift != nil }, set: { if !$0 { pinShift = nil } })) {
                let n = pinShift ?? 1; PINSheet(title: "Zmiana \(n)") { openedShift = n; pinShift = nil }
            }
            .toolbar { ToolbarItem(placement: .topBarTrailing) { Button("Wyloguj", systemImage: "rectangle.portrait.and.arrow.right") { store.logout() }.labelStyle(.iconOnly) } }
            .onReceive(timer) { now = $0 }
        }
    }
    var dueItems: [ReminderItem] { LocalReminders.items(store).filter { $0.annotation.reminder?.due(now: Operations.timestamp(now), account: store.account) == true } }
}
struct MenuTile: View {
    let title: String; let symbol: String; let color: Color; var height: CGFloat = 86
    var body: some View { VStack(spacing: 8) { Image(systemName: symbol).font(.title2); Text(title).font(.subheadline.bold()).multilineTextAlignment(.center).fixedSize(horizontal: false, vertical: true) }.frame(maxWidth: .infinity).frame(height: height).foregroundStyle(color).background(color.opacity(0.09), in: RoundedRectangle(cornerRadius: 14)).overlay(RoundedRectangle(cornerRadius: 14).stroke(color.opacity(0.25))) }
}
struct DocumentationView: View {
    var body: some View { ContentUnavailableView("Brak dokumentów", systemImage: "folder", description: Text("Miejsce na dokumentację produkcji.")).navigationTitle("Dokumentacja produkcji") }
}
struct OtherMenu: View {
    var body: some View { List {
        NavigationLink("Zbiorniki") { TanksView() }
        NavigationLink("Zbiorniki — przewidywanie produkcji") { EstimatesView() }
        NavigationLink("Wpisy") { NotesView(scope: 0, kind: "PRODUCTION", title: "Wpisy produkcji") }
        NavigationLink("Bieżące notatki") { NotesView(scope: 0, kind: "CURRENT_NOTES", title: "Bieżące notatki") }
    }.navigationTitle("Inne") }
}
struct ShiftMenu: View {
    let shift: Int
    @State private var labPIN = false; @State private var laboratory = false
    var body: some View { List {
        NavigationLink("Zbiorniki", destination: TanksView())
        NavigationLink("Notatki") { NotesView(scope: shift, kind: "CURRENT_NOTES", title: "Notatki · zmiana \(shift)") }
        NavigationLink("Przypomnienia") { NotesView(scope: shift, kind: "REMINDER", title: "Przypomnienia") }
        NavigationLink("Softlab") { ContentUnavailableView("Softlab", systemImage: "desktopcomputer", description: Text("Miejsce na opis działania programu.")) }
        Section("Kontrola parametrów") {
            NavigationLink("Klimatyzacja") { NotesView(scope: shift, kind: "AIR_CONDITIONING", title: "Klimatyzacja") }
            NavigationLink("Mycie") { NotesView(scope: shift, kind: "WASHING", title: "Mycie") }
            NavigationLink("Produkcja") { NotesView(scope: shift, kind: "PRODUCTION", title: "Produkcja") }
            NavigationLink("Bieżące notatki") { NotesView(scope: shift, kind: "CURRENT_NOTES", title: "Bieżące notatki") }
        }
        Button("Laboratorium") { labPIN = true }
    }.navigationTitle("Zmiana \(shift)")
        .sheet(isPresented: $labPIN) { PINSheet(title: "Laboratorium", expected: "2426") { laboratory = true } }
        .navigationDestination(isPresented: $laboratory) { TanksView(laboratory: true) }
    }
}
