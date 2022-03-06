package prog8.compilerinterface.intermediate

import prog8.ast.base.DataType
import prog8.ast.base.Position
import prog8.compilerinterface.Encoding


class PtAddressOf(position: Position) : PtNode(position) {
    val addr: PtNode
        get() = children.single()

    override fun printProperties() {}
}


class PtArrayIndexer(position: Position): PtNode(position) {
    val variable: PtIdentifier
        get() = children[0] as PtIdentifier
    val index: PtNode
        get() = children[1]

    override fun printProperties() {}
}


class PtArrayLiteral(val type: DataType, position: Position): PtNode(position) {
    override fun printProperties() {
        print(type)
    }
}


class PtBinaryExpression(val operator: String, position: Position): PtNode(position) {
    val left: PtNode
        get() = children[0]
    val right: PtNode
        get() = children[1]

    override fun printProperties() {
        print(operator)
    }
}


class PtContainmentCheck(position: Position): PtNode(position) {
    val element: PtNode
        get() = children[0]
    val iterable: PtNode
        get() = children[0]
    override fun printProperties() {}
}


class PtIdentifier(val name: List<String>, position: Position) : PtNode(position) {
    override fun printProperties() {
        print(name)
    }
}


class PtMemoryByte(position: Position) : PtNode(position) {
    val address: PtNode
        get() = children.single()
    override fun printProperties() {}
}


class PtNumber(val type: DataType, val number: Double, position: Position) : PtNode(position) {
    override fun printProperties() {
        print("$number ($type)")
    }
}


class PtPrefix(val operator: String, position: Position): PtNode(position) {
    val value: PtNode
        get() = children.single()

    override fun printProperties() {
        print(operator)
    }
}


class PtRange(position: Position) : PtNode(position) {
    val from: PtNode
        get() = children[0]
    val to: PtNode
        get() = children[1]
    val step: PtNode
        get() = children[2]

    override fun printProperties() {}
}


class PtString(val value: String, val encoding: Encoding, position: Position) : PtNode(position) {
    override fun printProperties() {
        print("$encoding:\"$value\"")
    }
}


class PtTypeCast(val type: DataType, position: Position) : PtNode(position) {
    override fun printProperties() {
        print(type)
    }
}