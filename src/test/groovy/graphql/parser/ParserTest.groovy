package graphql.parser

import graphql.language.*
import spock.lang.Specification

class ParserTest extends Specification {


    OperationDefinition getOperationDefinition(Document document) {
        ((OperationDefinition) document.definitions[0])
    }

    SelectionSet getRootSelectionSet(Document document) {
        getOperationDefinition(document).selectionSet
    }


    def "parse anonymous simple query"() {
        given:
        def input = "{ me }"

        when:
        Document document = new Parser().parseDocument(input)
        then:
        document.definitions.size() == 1
        document.definitions[0] instanceof OperationDefinition
        getOperationDefinition(document).operation == OperationDefinition.Operation.QUERY
        assertField(getOperationDefinition(document), "me")
    }


    def assertField(OperationDefinition operationDefinition, String fieldName) {
        Selection selection = operationDefinition.getSelectionSet().getSelections()[0]
        assert selection instanceof Field
        Field field = (Field) selection
        assert field.name == fieldName
        true
    }


    def "parse selectionSet for field"() {
        given:
        def input = "{ me { name } }"

        when:
        Document document = new Parser().parseDocument(input)
        def rootSelectionSet = getRootSelectionSet(document)

        then:
        getInnerField(rootSelectionSet).name == "name"
    }

    def "parse query with variable definition"() {
        given:
        def input = 'query getProfile($devicePicSize: Int){ me }'

        def expectedResult = new Document()
        def variableDefinition = new VariableDefinition("devicePicSize", new Type("Int"))
        def selectionSet = new SelectionSet([new Field("me")])
        def definition = new OperationDefinition("getProfile", OperationDefinition.Operation.QUERY, [variableDefinition], selectionSet)
        expectedResult.definitions.add(definition)

        when:
        Document document = new Parser().parseDocument(input)
        then:
        document == expectedResult

    }

    def "parse mutation"() {
        given:
        def input = 'mutation setName { setName(name: "Homer") { newName } }'

        when:
        Document document = new Parser().parseDocument(input)

        then:
        getOperationDefinition(document).operation == OperationDefinition.Operation.MUTATION
    }

    def "parse field arguments"() {
        given:
        def input = '{ user(id: 10, name: "homer", admin:true, floatValue: 3.04) }'

        def argument = new Argument("id", new IntValue(10))
        def argument2 = new Argument("name", new StringValue("homer"))
        def argument3 = new Argument("admin", new BooleanValue(true))
        def argument4 = new Argument("floatValue", new FloatValue(3.04))
        def field = new Field("user", [argument, argument2, argument3, argument4])
        def selectionSet = new SelectionSet([field])
        def operationDefinition = new OperationDefinition()
        operationDefinition.operation = OperationDefinition.Operation.QUERY
        operationDefinition.selectionSet = selectionSet
        def expectedResult = new Document([operationDefinition])

        when:
        Document document = new Parser().parseDocument(input)

        then:
        document == expectedResult
    }

    def "parse fragment and query"() {
        given:
        def input = """query withFragments {
                    user(id: 4) {
                        friends(first: 10) { ...friendFields }
                        mutualFriends(first: 10) { ...friendFields }
                      }
                    }

                    fragment friendFields on User {
                      id
                      name
                    profilePic(size: 50)
                }"""

        and: "expected query"
        def fragmentSpreadFriends = new FragmentSpread("friendFields")
        def selectionSetFriends = new SelectionSet([fragmentSpreadFriends])
        def friendsField = new Field("friends", [new Argument("first", new IntValue(10))], selectionSetFriends)

        def fragmentSpreadMutalFriends = new FragmentSpread("friendFields")
        def selectionSetMutalFriends = new SelectionSet([fragmentSpreadMutalFriends])
        def mutalFriendsField = new Field("mutualFriends", [new Argument("first", new IntValue(10))], selectionSetMutalFriends)

        def userField = new Field("user", [new Argument("id", new IntValue(4))], new SelectionSet([friendsField, mutalFriendsField]))

        def queryDefinition = new OperationDefinition("withFragments", OperationDefinition.Operation.QUERY, new SelectionSet([userField]))

        and: "expected fragment definition"
        def idField = new Field("id")
        def nameField = new Field("name")
        def profilePicField = new Field("profilePic", [new Argument("size", new IntValue(50))])
        def selectionSet = new SelectionSet([idField, nameField, profilePicField])
        def fragmentDefinition = new FragmentDefinition("friendFields", "User", selectionSet)


        when:
        Document document = new Parser().parseDocument(input)

        then:
        document.definitions.size() == 2
        document.definitions[0] == queryDefinition
        document.definitions[1] == fragmentDefinition
    }

    def "parse inline fragment"() {
        given:
        def input = """
                    query InlineFragmentTyping {
                      profiles(handles: ["zuck", "cocacola"]) {
                        handle
                        ... on User {
                          friends { count }
                        }
                        ... on Page {
                          likers { count }
                        }
                      }
                    }
                """

        and: "expected query definition"

        def handleField = new Field("handle")

        def userSelectionSet = new SelectionSet([new Field("friends", new SelectionSet([new Field("count")]))])
        def userFragment = new InlineFragment("User", userSelectionSet)

        def pageSelectionSet = new SelectionSet([new Field("likers", new SelectionSet([new Field("count")]))])
        def pageFragment = new InlineFragment("Page", pageSelectionSet)


        def handlesArgument = new ArrayValue([new StringValue("zuck"), new StringValue("cocacola")])
        def profilesField = new Field("profiles", [new Argument("handles", handlesArgument)], new SelectionSet([handleField, userFragment, pageFragment]))

        def queryDefinition = new OperationDefinition("InlineFragmentTyping", OperationDefinition.Operation.QUERY,
                new SelectionSet([profilesField]))

        when:
        def document = new Parser().parseDocument(input)

        then:
        document.definitions.size() == 1
        document.definitions[0] == queryDefinition

    }

    def "parse directives"() {
        given:
        def input = """
            query myQuery(\$someTest: Boolean) {
              experimentalField @if: \$someTest,
              controlField @unless: \$someTest }
            """

        and: "expected query"

        def experimentalField = new Field("experimentalField", [], [new Directive("if", new VariableReference("someTest"))])
        def controlField = new Field("controlField", [], [new Directive("unless", new VariableReference("someTest"))])
        def queryDefinition = new OperationDefinition("myQuery", OperationDefinition.Operation.QUERY,
                [new VariableDefinition("someTest", new Type("Boolean"))],
                new SelectionSet([experimentalField, controlField]))


        when:
        def document = new Parser().parseDocument(input)

        then:
        document.definitions[0] == queryDefinition


    }


    Field getInnerField(SelectionSet selectionSet) {
        def field = (Field) selectionSet.selections[0]
        (Field) field.selectionSet.selections[0]
    }


}