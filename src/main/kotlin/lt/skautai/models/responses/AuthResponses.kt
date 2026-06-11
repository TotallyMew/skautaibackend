package lt.skautai.models.responses

import kotlinx.serialization.Serializable
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.descriptors.element
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.encoding.decodeStructure
import kotlinx.serialization.encoding.encodeStructure

@Serializable
data class TokenResponse(
    val token: String,
    val refreshToken: String? = null,
    val userId: String,
    val email: String,
    val name: String,
    val type: String = "user",
    val tuntai: List<TuntasInfo> = emptyList()
)

@Serializable(with = MessageResponseSerializer::class)
data class MessageResponse(
    val message: String
)

@Serializable(with = ErrorResponseSerializer::class)
data class ErrorResponse(
    val error: String
)

object ErrorResponseSerializer : KSerializer<ErrorResponse> {
    override val descriptor = buildClassSerialDescriptor("ErrorResponse") {
        element<String>("error")
    }

    override fun serialize(encoder: Encoder, value: ErrorResponse) {
        encoder.encodeStructure(descriptor) {
            encodeStringElement(descriptor, 0, sanitizeUserMessage(value.error))
        }
    }

    override fun deserialize(decoder: Decoder): ErrorResponse {
        var error = "Užklausa nepavyko."
        decoder.decodeStructure(descriptor) {
            while (true) {
                when (val index = decodeElementIndex(descriptor)) {
                    0 -> error = decodeStringElement(descriptor, 0)
                    CompositeDecoder.DECODE_DONE -> break
                    else -> error("Unexpected index: $index")
                }
            }
        }
        return ErrorResponse(error)
    }
}

object MessageResponseSerializer : KSerializer<MessageResponse> {
    override val descriptor = buildClassSerialDescriptor("MessageResponse") {
        element<String>("message")
    }

    override fun serialize(encoder: Encoder, value: MessageResponse) {
        encoder.encodeStructure(descriptor) {
            encodeStringElement(descriptor, 0, sanitizeUserMessage(value.message))
        }
    }

    override fun deserialize(decoder: Decoder): MessageResponse {
        var message = "Veiksmas atliktas."
        decoder.decodeStructure(descriptor) {
            while (true) {
                when (val index = decodeElementIndex(descriptor)) {
                    0 -> message = decodeStringElement(descriptor, 0)
                    CompositeDecoder.DECODE_DONE -> break
                    else -> error("Unexpected index: $index")
                }
            }
        }
        return MessageResponse(message)
    }
}

private val technicalErrorMarkers = listOf(
    "org.jetbrains.exposed",
    "org.postgresql",
    "postgresql",
    "psqlexception",
    "sqlexception",
    "sqlstate",
    "jdbc:",
    "select ",
    "insert ",
    "update ",
    "delete ",
    " from ",
    " where ",
    "constraint",
    "duplicate key",
    "foreign key",
    "stacktrace",
    "exception:",
    "java.net.",
    "failed to connect to",
    "localhost",
    "10.0.2.2"
)

private val userMessageTranslations = mapOf(
    "Request failed" to "Užklausa nepavyko.",
    "Internal server error" to "Vidinė serverio klaida.",
    "Registration failed" to "Registracija nepavyko. Patikrinkite įvestus duomenis.",
    "Login failed" to "Neteisingas el. paštas arba slaptažodis.",
    "Refresh failed" to "Prisijungimas baigėsi. Prisijunkite iš naujo.",
    "Setup failed" to "Konfigūracija nepavyko.",
    "Invalid bootstrap token" to "Neteisingas pradinis prieigos kodas.",
    "Super admin access required" to "Reikalingos superadministratoriaus teisės.",
    "Insufficient permissions" to "Neturite teisių atlikti šį veiksmą.",
    "Not authenticated" to "Prisijungimas baigėsi. Prisijunkite iš naujo.",
    "Invalid token" to "Prisijungimas baigėsi. Prisijunkite iš naujo.",
    "Invalid refresh token" to "Prisijungimas baigėsi. Prisijunkite iš naujo.",
    "X-Tuntas-Id header required" to "Pirmiausia pasirinkite tuntą.",
    "Missing X-Tuntas-Id header" to "Pirmiausia pasirinkite tuntą.",
    "Invalid tuntas ID" to "Neteisingas tunto ID.",
    "Missing tuntas ID" to "Nenurodytas tuntas.",
    "Not a member of this tuntas" to "Nesate šio tunto narys.",
    "You are not an active member of this tuntas" to "Nesate aktyvus šio tunto narys.",
    "User ID required" to "Nenurodytas vartotojas.",
    "Invalid user ID" to "Neteisingas vartotojo ID.",
    "User not found" to "Vartotojas nerastas.",
    "Member not found" to "Narys nerastas.",
    "Reservation not found" to "Rezervacija nerasta.",
    "Item not found" to "Inventoriaus objektas nerastas.",
    "Item not found or not active" to "Inventoriaus objektas nerastas arba neaktyvus.",
    "Event not found" to "Renginys nerastas.",
    "Location not found" to "Vieta nerasta.",
    "Location not found or not active" to "Vieta nerasta.",
    "Organizational unit not found" to "Vienetas nerastas.",
    "Request not found" to "Prašymas nerastas.",
    "Request is not accessible" to "Prašymas nepasiekiamas.",
    "Kit not found" to "Rinkinys nerastas.",
    "Inventory kit not found" to "Inventoriaus rinkinys nerastas.",
    "Template not found" to "Šablonas nerastas.",
    "Role not found" to "Pareigos nerastos.",
    "Rank not found" to "Laipsnis nerastas.",
    "Purchase not found" to "Pirkimas nerastas.",
    "Invoice file not found" to "Sąskaitos failas nerastas.",
    "Shared item not found" to "Bendro inventoriaus objektas nerastas.",
    "Shared inventory item not found" to "Bendro inventoriaus objektas nerastas.",
    "Unit item not found" to "Vieneto inventoriaus objektas nerastas.",
    "Source shared item not found" to "Pradinis bendro inventoriaus objektas nerastas.",
    "Duplicate target item not found" to "Pasirinktas dublikato objektas nerastas.",
    "Responsible user not found" to "Atsakingas narys nerastas.",
    "Invalid status" to "Neteisinga būsena.",
    "Invalid event type" to "Neteisingas renginio tipas.",
    "Invalid inventory type" to "Neteisingas inventoriaus tipas.",
    "Invalid duplicate handling option" to "Neteisingas dublikato tvarkymo pasirinkimas.",
    "Invalid custodian ID" to "Neteisingas saugotojo ID.",
    "Invalid location ID" to "Neteisingas vietos ID.",
    "Invalid responsible user ID" to "Neteisingas atsakingo nario ID.",
    "Invalid purchase date format, use YYYY-MM-DD" to "Neteisingas pirkimo datos formatas. Naudokite YYYY-MM-DD.",
    "Invalid neededByDate format, use YYYY-MM-DD" to "Neteisingas reikalingumo datos formatas. Naudokite YYYY-MM-DD.",
    "Invalid duplicate target item ID" to "Neteisingas dublikato objekto ID.",
    "Invalid source shared item ID" to "Neteisingas pradinio bendro inventoriaus objekto ID.",
    "Invalid target unit ID" to "Neteisingas tikslinio vieneto ID.",
    "Invalid requesting unit ID" to "Neteisingas prašančio vieneto ID.",
    "Invalid item ID" to "Neteisingas inventoriaus objekto ID.",
    "Invalid event ID" to "Neteisingas renginio ID.",
    "Invalid role ID" to "Neteisingas pareigų ID.",
    "Invalid kit ID" to "Neteisingas rinkinio ID.",
    "Invalid bucket ID" to "Neteisingas plano skilties ID.",
    "Invalid inventory item ID" to "Neteisingas inventoriaus objekto ID.",
    "Invalid inventory source ID" to "Neteisingas inventoriaus šaltinio ID.",
    "Invalid allocation ID" to "Neteisingas paskirstymo ID.",
    "Invalid purchase ID" to "Neteisingas pirkimo ID.",
    "Invalid purchase item ID" to "Neteisingas pirkimo objekto ID.",
    "Invalid member ID" to "Neteisingas nario ID.",
    "Invalid invoice file name" to "Neteisingas sąskaitos failo pavadinimas.",
    "Invalid email or password" to "Neteisingas el. paštas arba slaptažodis.",
    "Email already registered" to "Šis el. paštas jau užregistruotas.",
    "Tuntas name already exists" to "Tuntas tokiu pavadinimu jau yra.",
    "Invalid invite code" to "Neteisingas pakvietimo kodas.",
    "Invite code already used" to "Pakvietimo kodas jau panaudotas.",
    "Unknown role type" to "Nežinomas pareigų tipas.",
    "Super admin not found" to "Superadministratorius nerastas.",
    "Super admin already exists" to "Superadministratorius jau yra.",
    "Failed to update profile" to "Profilio atnaujinti nepavyko.",
    "Failed to update password" to "Slaptažodžio pakeisti nepavyko.",
    "Failed to leave tuntas" to "Tunto palikti nepavyko.",
    "Approval failed" to "Patvirtinti nepavyko.",
    "Rejection failed" to "Atmesti nepavyko.",
    "Failed to fetch organizational units" to "Vienetų gauti nepavyko.",
    "Failed to fetch members" to "Narių gauti nepavyko.",
    "Failed to assign leadership role" to "Vadovavimo pareigų priskirti nepavyko.",
    "Failed to update leadership role" to "Vadovavimo pareigų atnaujinti nepavyko.",
    "Failed to remove leadership role" to "Vadovavimo pareigų pašalinti nepavyko.",
    "Failed to assign rank" to "Laipsnio priskirti nepavyko.",
    "Failed to remove rank" to "Laipsnio pašalinti nepavyko.",
    "Failed to create inventory item" to "Inventoriaus objekto sukurti nepavyko.",
    "User already has an event staff role" to "Narys jau turi renginio štabo pareigas.",
    "At least one inventory item is required" to "Įveskite bent vieną inventoriaus objektą.",
    "Planned quantity must be at least 1" to "Planuojamas kiekis turi būti bent 1.",
    "Returns can be reconciled only during wrap-up" to "Grąžinimus galima suderinti tik renginio užbaigimo metu.",
    "Invalid return decision" to "Neteisingas grąžinimo sprendimas.",
    "File not found" to "Failas nerastas.",
    "Content-Type is required" to "Nurodykite failo tipą.",
    "Original file name is required" to "Nurodykite pradinį failo pavadinimą.",
    "Document file required" to "Įkelkite dokumento failą.",
    "Invalid file name" to "Neteisingas failo pavadinimas.",
    "Unsupported file type" to "Nepalaikomas failo tipas.",
    "File contents do not match the declared type" to "Failo turinys neatitinka nurodyto tipo.",
    "Name is required" to "Įveskite vardą.",
    "Name must be at least 2 characters" to "Vardas turi būti bent 2 simbolių.",
    "Name must be at most 100 characters" to "Vardas negali būti ilgesnis nei 100 simbolių.",
    "Name contains invalid characters" to "Varde naudokite tik raides, tarpus, brūkšnį arba apostrofą.",
    "Surname is required" to "Įveskite pavardę.",
    "Surname must be at least 2 characters" to "Pavardė turi būti bent 2 simbolių.",
    "Surname must be at most 100 characters" to "Pavardė negali būti ilgesnė nei 100 simbolių.",
    "Surname contains invalid characters" to "Pavardėje naudokite tik raides, tarpus, brūkšnį arba apostrofą.",
    "Email is required" to "Įveskite el. paštą.",
    "Email must be at most 255 characters" to "El. paštas negali būti ilgesnis nei 255 simboliai.",
    "Password is required" to "Įveskite slaptažodį.",
    "Krastas is required" to "Įveskite kraštą.",
    "Invalid krastas" to "Neteisingas kraštas.",
    "Invalid email format" to "Įveskite teisingą el. pašto adresą.",
    "Password must be at least 8 characters" to "Slaptažodis turi būti bent 8 simbolių.",
    "Password must be at most 128 characters" to "Slaptažodis negali būti ilgesnis nei 128 simboliai.",
    "Password cannot contain spaces" to "Slaptažodyje negali būti tarpų.",
    "Password must contain a letter" to "Slaptažodyje turi būti bent viena raidė.",
    "Password must contain a number" to "Slaptažodyje turi būti bent vienas skaičius.",
    "Invalid phone format" to "Įveskite teisingą telefono numerį.",
    "Phone must be at most 20 characters" to "Telefono numeris negali būti ilgesnis nei 20 simbolių.",
    "Phone must contain at least 5 digits" to "Telefono numeryje turi būti bent 5 skaičiai.",
    "Phone must contain at most 15 digits" to "Telefono numeryje negali būti daugiau nei 15 skaičių.",
    "Tuntas name is required" to "Įveskite tunto pavadinimą.",
    "Tuntas name must be at least 2 characters" to "Tunto pavadinimas turi būti bent 2 simbolių.",
    "Tuntas name must be at most 100 characters" to "Tunto pavadinimas negali būti ilgesnis nei 100 simbolių.",
    "Tuntas name must contain a letter" to "Tunto pavadinime turi būti bent viena raidė.",
    "Tuntas name contains invalid characters" to "Tunto pavadinime naudokite tik raides, skaičius, tarpus ir įprastus skyrybos ženklus.",
    "Invite code is required" to "Įveskite pakvietimo kodą.",
    "Invite code must be at most 20 characters" to "Pakvietimo kodas negali būti ilgesnis nei 20 simbolių.",
    "Current password is required" to "Įveskite dabartinį slaptažodį.",
    "Invalid current password" to "Dabartinis slaptažodis neteisingas.",
    "New password must be different" to "Naujas slaptažodis turi skirtis nuo dabartinio.",
    "Too many failed login attempts. Please try again later." to "Per daug nesėkmingų bandymų prisijungti. Pabandykite vėliau.",
    "Item with the same name already exists" to "Inventoriaus objektas tokiu pavadinimu jau yra.",
    "Item name is required" to "Įveskite inventoriaus objekto pavadinimą.",
    "Quantity must be at least 1" to "Kiekis turi būti bent 1.",
    "Custodian unit not found in this tuntas" to "Saugotojo vienetas šiame tunte nerastas.",
    "Target unit not found in this tuntas" to "Tikslinis vienetas šiame tunte nerastas.",
    "Only shared tuntas inventory can be transferred" to "Perduoti galima tik bendrą tunto inventorių.",
    "Only active shared inventory can be transferred" to "Perduoti galima tik aktyvų bendrą inventorių.",
    "Only unit inventory can be returned" to "Grąžinti galima tik vieneto inventorių.",
    "Only transferred shared inventory can be returned" to "Grąžinti galima tik iš bendro inventoriaus perduotus daiktus.",
    "Only active unit inventory can be returned" to "Grąžinti galima tik aktyvų vieneto inventorių.",
    "Cannot update an inactive item" to "Neaktyvaus inventoriaus objekto atnaujinti negalima.",
    "Item is already inactive" to "Inventoriaus objektas jau neaktyvus.",
    "Write-off reason is required" to "Įveskite nurašymo priežastį.",
    "Decision must be APPROVED or REJECTED" to "Sprendimas turi būti patvirtinimas arba atmetimas.",
    "Rejection reason is required" to "Įveskite atmetimo priežastį.",
    "Custom field name is required" to "Įveskite papildomo lauko pavadinimą.",
    "Custom field name must be at most 100 characters" to "Papildomo lauko pavadinimas gali būti iki 100 simbolių.",
    "Inventory category is required" to "Pasirinkite inventoriaus kategoriją.",
    "Inventory category must be at most 30 characters" to "Inventoriaus kategorija gali būti iki 30 simbolių.",
    "Item condition is required" to "Pasirinkite inventoriaus būklę.",
    "Item condition must be at most 30 characters" to "Inventoriaus būklė gali būti iki 30 simbolių.",
    "Responsible user must belong to this tuntas" to "Atsakingas narys turi priklausyti šiam tuntui.",
    "Requesting unit is required" to "Pasirinkite prašantį vienetą.",
    "Requesting unit not found in this tuntas" to "Prašantis vienetas šiame tunte nerastas.",
    "Only shared tuntas inventory can be requested" to "Prašyti galima tik bendro tunto inventoriaus.",
    "Action must be FORWARDED or REJECTED" to "Veiksmas turi būti persiuntimas arba atmetimas.",
    "Action must be APPROVED or REJECTED" to "Veiksmas turi būti patvirtinimas arba atmetimas.",
    "Request must be forwarded by unit leader first" to "Prašymą pirmiausia turi persiųsti vieneto vadovas.",
    "You can only create a request for your own unit" to "Prašymą galite kurti tik savo vienetui.",
    "You can only cancel your own requests" to "Galite atšaukti tik savo prašymus.",
    "Request cannot be cancelled in its current state" to "Šios būsenos prašymo atšaukti negalima.",
    "Only shared inventory items can be transferred" to "Perduoti galima tik bendro inventoriaus objektus.",
    "Super admin created successfully" to "Superadministratorius sukurtas.",
    "Template deleted" to "Šablonas ištrintas.",
    "Request cancelled" to "Prašymas atšauktas.",
    "Inventory kit deactivated" to "Inventoriaus rinkinys išjungtas.",
    "Leadership role removed" to "Vadovavimo pareigos pašalintos.",
    "Leadership role resigned" to "Vadovavimo pareigų atsisakyta.",
    "Tuntininkas role transferred" to "Tuntininko pareigos perleistos.",
    "Rank removed" to "Laipsnis pašalintas.",
    "Member removed" to "Narys pašalintas.",
    "Successfully resigned from tuntas" to "Iš tunto išeita.",
    "Event cancelled" to "Renginys atšauktas.",
    "Inventory bucket deleted" to "Inventoriaus plano skiltis ištrinta.",
    "Inventory item deleted" to "Inventoriaus objektas ištrintas.",
    "Inventory source deleted" to "Inventoriaus šaltinis ištrintas.",
    "Inventory allocation deleted" to "Inventoriaus paskirstymas ištrintas.",
    "Pastovykle deleted" to "Pastovyklė ištrinta.",
    "Pastovyklės bendravadovis pašalintas" to "Pastovyklės bendravadovis pašalintas.",
    "Pastovyklės narys pašalintas" to "Pastovyklės narys pašalintas.",
    "Inventory assignment removed" to "Inventoriaus priskyrimas pašalintas.",
    "Unit deleted" to "Vienetas ištrintas.",
    "Left unit" to "Vienetas paliktas.",
    "Member removed from draugove" to "Narys pašalintas iš vieneto.",
    "Location deleted" to "Vieta ištrinta.",
    "Reservation cancelled" to "Rezervacija atšaukta.",
    "Item deactivated" to "Inventoriaus objektas išjungtas.",
    "Tuntas approved successfully" to "Tuntas patvirtintas.",
    "Tuntas rejected" to "Tuntas atmestas.",
    "Password updated" to "Slaptažodis pakeistas.",
    "Left tuntas" to "Tuntas paliktas."
)

private fun sanitizeUserMessage(message: String): String {
    val normalized = message.lowercase()
    if (technicalErrorMarkers.any { it in normalized }) {
        return "Užklausa nepavyko."
    }
    userMessageTranslations[message]?.let { return it }
    return genericLithuanianMessage(message) ?: message
}

private fun genericLithuanianMessage(message: String): String? {
    val normalized = message.lowercase()
    return when {
        "not found" in normalized -> "Objektas nerastas."
        "failed to" in normalized || " failed" in normalized -> "Veiksmas nepavyko. Bandykite dar kartą."
        "invalid" in normalized -> "Neteisingi duomenys."
        "required" in normalized -> "Užpildykite privalomus laukus."
        "already" in normalized -> "Toks įrašas jau yra."
        "cannot" in normalized -> "Šio veiksmo atlikti negalima."
        "only " in normalized -> "Neturite teisių atlikti šį veiksmą."
        "must " in normalized -> "Patikrinkite įvestus duomenis."
        "unknown" in normalized -> "Nežinoma reikšmė."
        else -> null
    }
}
