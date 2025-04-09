@file:Suppress(
    "ArrayInDataClass",
    "EnumEntryName",
    "RemoveRedundantQualifierName",
    "UnusedImport"
)

package no.kartverket.altinn3.models


import com.fasterxml.jackson.annotation.JsonProperty

enum class ResourceType(val value: kotlin.String) {

    @JsonProperty(value = "Default")
    Default("default"),

    @JsonProperty(value = "Systemresource")
    Systemresource("systemresource"),

    @JsonProperty(value = "Maskinportenschema")
    Maskinportenschema("maskinportenschema"),

    @JsonProperty(value = "Altinn2service")
    Altinn2service("altinn2service"),

    @JsonProperty(value = "Altinnapp")
    Altinnapp("altinnapp"),

    @JsonProperty(value = "Genericaccessresource")
    Genericaccessresource("genericaccessresource"),

    @JsonProperty(value = "Brokerservice")
    Brokerservice("brokerservice"),

    @JsonProperty(value = "Correspondenceservice")
    Correspondenceservice("correspondenceservice");

    /**
     * Override [toString()] to avoid using the enum variable name as the value, and instead use
     * the actual value defined in the API spec file.
     *
     * This solves a problem when the variable name and its value are different, and ensures that
     * the client sends the correct enum values to the server always.
     */
    override fun toString(): kotlin.String = value

    companion object {
        /**
         * Converts the provided [data] to a [String] on success, null otherwise.
         */
        fun encode(data: kotlin.Any?): kotlin.String? = if (data is ResourceType) "$data" else null

        /**
         * Returns a valid [ResourceType] for [data], null otherwise.
         */
        fun decode(data: kotlin.Any?): ResourceType? = data?.let {
          val normalizedData = "$it".lowercase()
          entries.firstOrNull { value ->
            it == value || normalizedData == "$value".lowercase()
          }
        }
    }
}

