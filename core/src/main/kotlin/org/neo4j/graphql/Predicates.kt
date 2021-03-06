package org.neo4j.graphql

import graphql.Scalars
import graphql.language.NullValue
import graphql.language.ObjectValue
import graphql.language.Value
import graphql.schema.*
import org.neo4j.cypherdsl.core.*
import org.slf4j.LoggerFactory

typealias CypherDSL = org.neo4j.cypherdsl.core.Cypher

enum class FieldOperator(
        val suffix: String,
        val op: String,
        private val conditionCreator: (Expression, Expression) -> Condition,
        val not: Boolean = false,
        val requireParam: Boolean = true,
        val distance: Boolean = false
) {
    EQ("", "=", { lhs, rhs -> lhs.isEqualTo(rhs) }),
    IS_NULL("", "", { lhs, _ -> lhs.isNull }, requireParam = false),
    IS_NOT_NULL("_not", "", { lhs, _ -> lhs.isNotNull }, true, requireParam = false),
    NEQ("_not", "=", { lhs, rhs -> lhs.isEqualTo(rhs).not() }, true),
    GTE("_gte", ">=", { lhs, rhs -> lhs.gte(rhs) }),
    GT("_gt", ">", { lhs, rhs -> lhs.gt(rhs) }),
    LTE("_lte", "<=", { lhs, rhs -> lhs.lte(rhs) }),
    LT("_lt", "<", { lhs, rhs -> lhs.lt(rhs) }),

    NIN("_not_in", "IN", { lhs, rhs -> lhs.`in`(rhs).not() }, true),
    IN("_in", "IN", { lhs, rhs -> lhs.`in`(rhs) }),
    NC("_not_contains", "CONTAINS", { lhs, rhs -> lhs.contains(rhs).not() }, true),
    NSW("_not_starts_with", "STARTS WITH", { lhs, rhs -> lhs.startsWith(rhs).not() }, true),
    NEW("_not_ends_with", "ENDS WITH", { lhs, rhs -> lhs.endsWith(rhs).not() }, true),
    C("_contains", "CONTAINS", { lhs, rhs -> lhs.contains(rhs) }),
    SW("_starts_with", "STARTS WITH", { lhs, rhs -> lhs.startsWith(rhs) }),
    EW("_ends_with", "ENDS WITH", { lhs, rhs -> lhs.endsWith(rhs) }),
    MATCHES("_matches", "=~", {lhs, rhs -> lhs.matches(rhs) }),


    DISTANCE(NEO4j_POINT_DISTANCE_FILTER_SUFFIX, "=", { lhs, rhs -> lhs.isEqualTo(rhs) }, distance = true),
    DISTANCE_LT(NEO4j_POINT_DISTANCE_FILTER_SUFFIX + "_lt", "<", { lhs, rhs -> lhs.lt(rhs) }, distance = true),
    DISTANCE_LTE(NEO4j_POINT_DISTANCE_FILTER_SUFFIX + "_lte", "<=", { lhs, rhs -> lhs.lte(rhs)  }, distance = true),
    DISTANCE_GT(NEO4j_POINT_DISTANCE_FILTER_SUFFIX + "_gt", ">", { lhs, rhs -> lhs.gt(rhs)  }, distance = true),
    DISTANCE_GTE(NEO4j_POINT_DISTANCE_FILTER_SUFFIX + "_gte", ">=", { lhs, rhs -> lhs.gte(rhs) }, distance = true);

    val list = op == "IN"

    fun resolveCondition(variablePrefix: String, queriedField: String, propertyContainer: PropertyContainer, field: GraphQLFieldDefinition?, value: Any, suffix: String? = null): List<Condition> {
        return if (field?.type?.isNeo4jType() == true && value is ObjectValue) {
            resolveNeo4jTypeConditions(variablePrefix, queriedField, propertyContainer, field, value, suffix)
        } else if (field?.isNativeId() == true) {
            val id = propertyContainer.id()
            val parameter = queryParameter(value, variablePrefix, queriedField, suffix)
            val condition = if (list) {
                val idVar = CypherDSL.name("id")
                conditionCreator(id, CypherDSL.listWith(idVar).`in`(parameter).returning(CypherDSL.call("toInteger").withArgs(idVar).asFunction()))
            } else {
                conditionCreator(id, CypherDSL.call("toInteger").withArgs(parameter).asFunction())
            }
            listOf(condition)
        } else {
            resolveCondition(variablePrefix, queriedField, propertyContainer.property(field?.propertyName()
                    ?: queriedField), value, suffix)
        }
    }

    private fun resolveNeo4jTypeConditions(variablePrefix: String, queriedField: String, propertyContainer: PropertyContainer, field: GraphQLFieldDefinition, value: ObjectValue, suffix: String?): List<Condition> {
        val neo4jTypeConverter = getNeo4jTypeConverter(field)
        val conditions = mutableListOf<Condition>()
        if (distance){
            val parameter = queryParameter(value, variablePrefix, queriedField, suffix)
            conditions += (neo4jTypeConverter as Neo4jPointConverter).createDistanceCondition(
                    propertyContainer.property(field.propertyName()),
                    parameter,
                    conditionCreator
            )
        }  else {
            value.objectFields.forEachIndexed { index, objectField ->
                val parameter = queryParameter(value, variablePrefix, queriedField, if (value.objectFields.size > 1) "And${index + 1}" else null, suffix, objectField.name)
                    .withValue(objectField.value.toJavaValue())

                conditions += neo4jTypeConverter.createCondition(objectField, field, parameter, conditionCreator, propertyContainer)
            }
        }
        return conditions
    }

    private fun resolveCondition(variablePrefix: String, queriedField: String, property: Property, value: Any, suffix: String?): List<Condition> {
        val parameter = queryParameter(value, variablePrefix, queriedField, suffix)
        val condition = conditionCreator(property, parameter)
        return listOf(condition)
    }

    companion object {

        fun resolve(queriedField: String, field: GraphQLFieldDefinition, value: Any?): FieldOperator? {
            val fieldName = field.name
            if (value == null) {
                return listOf(IS_NULL, IS_NOT_NULL).find { queriedField == fieldName + it.suffix }
            }
            val ops = enumValues<FieldOperator>().filterNot { it == IS_NULL || it == IS_NOT_NULL }
            return ops.find { queriedField == fieldName + it.suffix }
                    ?: if (field.type.isNeo4jSpatialType()) {
                        ops.find { queriedField == fieldName + NEO4j_POINT_DISTANCE_FILTER_SUFFIX + it.suffix }
                    } else {
                        null
                    }
        }

        fun forType(type: GraphQLType): List<FieldOperator> =
                when {
                    type == Scalars.GraphQLBoolean -> listOf(EQ, NEQ)
                    type.innerName() == NEO4j_POINT_DISTANCE_FILTER -> listOf(EQ, LT, LTE, GT, GTE)
                    type.isNeo4jSpatialType() -> listOf(EQ, NEQ)
                    type.isNeo4jType() -> listOf(EQ, NEQ, IN, NIN)
                    type is GraphQLFieldsContainer || type is GraphQLInputObjectType -> throw IllegalArgumentException("This operators are not for relations, use the RelationOperator instead")
                    type is GraphQLEnumType -> listOf(EQ, NEQ, IN, NIN)
                    // todo list types
                    !type.isScalar() -> listOf(EQ, NEQ, IN, NIN)
                    else -> listOf(EQ, NEQ, IN, NIN, LT, LTE, GT, GTE) +
                            if (type.name() == "String" || type.name() == "ID") listOf(C, NC, SW, NSW, EW, NEW, MATCHES) else emptyList()
                }
    }

    fun fieldName(fieldName: String) = fieldName + suffix
}

enum class RelationOperator(val suffix: String, val op: String) {
    SOME("_some", "ANY"),

    EVERY("_every", "ALL"),

    SINGLE("_single", "SINGLE"),
    NONE("_none", "NONE"),

    // `eq` if queried with an object, `not exists` if  queried with null
    EQ_OR_NOT_EXISTS("", ""),
    NOT("_not", "");

    fun fieldName(fieldName: String) = fieldName + suffix

    fun harmonize(type: GraphQLFieldsContainer, field: GraphQLFieldDefinition, value: Value<*>, queryFieldName: String) = when (field.type.isList()) {
        true -> when (this) {
            NOT -> when (value) {
                is NullValue -> NOT
                else -> NONE
            }
            EQ_OR_NOT_EXISTS -> when (value) {
                is NullValue -> EQ_OR_NOT_EXISTS
                else -> {
                    LOGGER.debug("$queryFieldName on type ${type.name} was used for filtering, consider using ${field.name}${EVERY.suffix} instead")
                    EVERY
                }
            }
            else -> this
        }
        false -> when (this) {
            SINGLE -> {
                LOGGER.debug("Using $queryFieldName on type ${type.name} is deprecated, use ${field.name} directly")
                SOME
            }
            SOME -> {
                LOGGER.debug("Using $queryFieldName on type ${type.name} is deprecated, use ${field.name} directly")
                SOME
            }
            NONE -> {
                LOGGER.debug("Using $queryFieldName on type ${type.name} is deprecated, use ${field.name}${NOT.suffix} instead")
                NONE
            }
            NOT -> when (value) {
                is NullValue -> NOT
                else -> NONE
            }
            EQ_OR_NOT_EXISTS -> when (value) {
                is NullValue -> EQ_OR_NOT_EXISTS
                else -> SOME
            }
            else -> this
        }
    }

    companion object {
        private val LOGGER = LoggerFactory.getLogger(RelationOperator::class.java)

        fun createRelationFilterFields(type: GraphQLFieldsContainer, field: GraphQLFieldDefinition, filterType: String, builder: GraphQLInputObjectType.Builder) {
            val list = field.type.isList()

            val addFilterField = { op: RelationOperator, description: String ->
                builder.addFilterField(op.fieldName(field.name), false, filterType, description)
            }

            addFilterField(EQ_OR_NOT_EXISTS, "Filters only those `${type.name}` for which ${if (list) "all" else "the"} `${field.name}`-relationship matches this filter. " +
                    "If `null` is passed to this field, only those `${type.name}` will be filtered which has no `${field.name}`-relations")

            addFilterField(NOT, "Filters only those `${type.name}` for which ${if (list) "all" else "the"} `${field.name}`-relationship does not match this filter. " +
                    "If `null` is passed to this field, only those `${type.name}` will be filtered which has any `${field.name}`-relation")
            if (list) {
                // n..m
                addFilterField(EVERY, "Filters only those `${type.name}` for which all `${field.name}`-relationships matches this filter")
                addFilterField(SOME, "Filters only those `${type.name}` for which at least one `${field.name}`-relationship matches this filter")
                addFilterField(SINGLE, "Filters only those `${type.name}` for which exactly one `${field.name}`-relationship matches this filter")
                addFilterField(NONE, "Filters only those `${type.name}` for which none of the `${field.name}`-relationships matches this filter")
            } else {
                // n..1
                addFilterField(SINGLE, "@deprecated Use the `${field.name}`-field directly (without any suffix)")
                addFilterField(SOME, "@deprecated Use the `${field.name}`-field directly (without any suffix)")
                addFilterField(NONE, "@deprecated Use the `${field.name}${NOT.suffix}`-field")
            }
        }
    }
}
