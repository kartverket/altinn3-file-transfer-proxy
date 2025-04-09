@file:Suppress(
    "ArrayInDataClass",
    "EnumEntryName",
    "RemoveRedundantQualifierName",
    "UnusedImport"
)

package no.kartverket.altinn3.models


import com.fasterxml.jackson.annotation.JsonProperty

/**
 * JSON Patch operation types.
 *
 * Values: `null`,add,remove,replace,move,copy,test
 */

enum class JsonPatchOperationType(val value: kotlin.String) {

    @JsonProperty(value = "null")
    `null`("null"),

    @JsonProperty(value = "add")
    add("add"),

    @JsonProperty(value = "remove")
    remove("remove"),

    @JsonProperty(value = "replace")
    replace("replace"),

    @JsonProperty(value = "move")
    move("move"),

    @JsonProperty(value = "copy")
    copy("copy"),

    @JsonProperty(value = "test")
    test("test");

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
        fun encode(data: kotlin.Any?): kotlin.String? = if (data is JsonPatchOperationType) "$data" else null

        /**
         * Returns a valid [JsonPatchOperationType] for [data], null otherwise.
         */
        fun decode(data: kotlin.Any?): JsonPatchOperationType? = data?.let {
            val normalizedData = "$it".lowercase()
            values().firstOrNull { value ->
                it == value || normalizedData == "$value".lowercase()
            }
        }
    }
}

