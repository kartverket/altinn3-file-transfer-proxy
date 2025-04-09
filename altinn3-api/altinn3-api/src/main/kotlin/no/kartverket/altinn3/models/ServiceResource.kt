
@file:Suppress(
    "ArrayInDataClass",
    "EnumEntryName",
    "RemoveRedundantQualifierName",
    "UnusedImport"
)

package no.kartverket.altinn3.models

import no.kartverket.altinn3.models.AuthorizationReferenceAttribute
import no.kartverket.altinn3.models.CompetentAuthority
import no.kartverket.altinn3.models.ContactPoint
import no.kartverket.altinn3.models.Keyword
import no.kartverket.altinn3.models.ResourcePartyType
import no.kartverket.altinn3.models.ResourceReference
import no.kartverket.altinn3.models.ResourceType

import com.fasterxml.jackson.annotation.JsonProperty

data class ServiceResource (

    @get:JsonProperty("identifier")
    val identifier: kotlin.String,

    @get:JsonProperty("title")
    val title: kotlin.collections.Map<kotlin.String, kotlin.String>,

    @get:JsonProperty("description")
    val description: kotlin.collections.Map<kotlin.String, kotlin.String>,

    @get:JsonProperty("contactPoints")
    val contactPoints: kotlin.collections.List<ContactPoint>,

    @get:JsonProperty("hasCompetentAuthority")
    val hasCompetentAuthority: CompetentAuthority,

    @get:JsonProperty("version")
    val version: kotlin.String? = null,

    @get:JsonProperty("rightDescription")
    val rightDescription: kotlin.collections.Map<kotlin.String, kotlin.String>? = null,

    @get:JsonProperty("homepage")
    val homepage: kotlin.String? = null,

    @get:JsonProperty("status")
    val status: kotlin.String? = null,

    @get:JsonProperty("spatial")
    val spatial: kotlin.collections.List<kotlin.String>? = null,

    @get:JsonProperty("produces")
    val produces: kotlin.collections.List<kotlin.String>? = null,

    @get:JsonProperty("isPartOf")
    val isPartOf: kotlin.String? = null,

    @get:JsonProperty("thematicAreas")
    val thematicAreas: kotlin.collections.List<kotlin.String>? = null,

    @get:JsonProperty("resourceReferences")
    val resourceReferences: kotlin.collections.List<ResourceReference>? = null,

    @get:JsonProperty("delegable")
    val delegable: kotlin.Boolean? = null,

    @get:JsonProperty("visible")
    val visible: kotlin.Boolean? = null,

    @get:JsonProperty("keywords")
    val keywords: kotlin.collections.List<Keyword>? = null,

    @get:JsonProperty("limitedByRRR")
    val limitedByRRR: kotlin.Boolean? = null,

    @get:JsonProperty("selfIdentifiedUserEnabled")
    val selfIdentifiedUserEnabled: kotlin.Boolean? = null,

    @get:JsonProperty("enterpriseUserEnabled")
    val enterpriseUserEnabled: kotlin.Boolean? = null,

    @get:JsonProperty("resourceType")
    val resourceType: ResourceType? = null,

    @get:JsonProperty("availableForType")
    val availableForType: kotlin.collections.List<ResourcePartyType>? = null,

    @get:JsonProperty("authorizationReference")
    val authorizationReference: kotlin.collections.List<AuthorizationReferenceAttribute>? = null,

    @get:JsonProperty("accessListMode")
    val accessListMode: kotlin.String? = null

) {


}

