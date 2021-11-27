package prog8.compilerinterface

import prog8.ast.expressions.Expression
import prog8.ast.statements.Subroutine

interface ICompilationTarget: IStringEncoding, IMemSizer {
    val name: String
    val machine: IMachineDefinition
    override fun encodeString(str: String, altEncoding: Boolean): List<UByte>
    override fun decodeString(bytes: List<UByte>, altEncoding: Boolean): String

    fun asmsubArgsEvalOrder(sub: Subroutine): List<Int>
    fun asmsubArgsHaveRegisterClobberRisk(args: List<Expression>): Boolean
}
