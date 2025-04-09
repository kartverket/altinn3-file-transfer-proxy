
package no.kartverket.altinn3.models

data class PartyUrn (
    val party: String
) {
    val PARTY_ID = Regex("^urn:altinn:party:id:.+\$")
    val PARTY_UUID = Regex("^urn:altinn:party:uuid:.+\$")
    val ORGANIZATION_IDENTIFIER = Regex("^urn:altinn:party:organisationid:.+\$")

    init{
        assert(PARTY_ID.matches(party)
            .or(PARTY_UUID.matches(party))
            .or(ORGANIZATION_IDENTIFIER.matches(party)))
    }
}
