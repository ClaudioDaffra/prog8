package prog8.astvm

import prog8.ast.*
import prog8.compiler.HeapValues
import prog8.compiler.RuntimeValue

class VariablesCreator(private val runtimeVariables: RuntimeVariables, private val heap: HeapValues) : IAstProcessor {

    override fun process(program: Program) {
        // define the three registers as global variables
        runtimeVariables.define(program.namespace, Register.A.name, RuntimeValue(DataType.UBYTE, 0))
        runtimeVariables.define(program.namespace, Register.X.name, RuntimeValue(DataType.UBYTE, 0))
        runtimeVariables.define(program.namespace, Register.Y.name, RuntimeValue(DataType.UBYTE, 0))

        val globalpos = Position("<<global>>", 0, 0, 0)
        val vdA = VarDecl(VarDeclType.VAR, DataType.UBYTE, false, null, false, Register.A.name, LiteralValue.optimalInteger(0, globalpos), globalpos)
        val vdX = VarDecl(VarDeclType.VAR, DataType.UBYTE, false, null, false, Register.X.name, LiteralValue.optimalInteger(0, globalpos), globalpos)
        val vdY = VarDecl(VarDeclType.VAR, DataType.UBYTE, false, null, false, Register.Y.name, LiteralValue.optimalInteger(0, globalpos), globalpos)
        vdA.linkParents(program.namespace)
        vdX.linkParents(program.namespace)
        vdY.linkParents(program.namespace)
        program.namespace.statements.add(vdA)
        program.namespace.statements.add(vdX)
        program.namespace.statements.add(vdY)

        super.process(program)
    }

    override fun process(decl: VarDecl): IStatement {
        if(decl.type==VarDeclType.VAR) {
            val value = when (decl.datatype) {
                in NumericDatatypes -> {
                    if(decl.value !is LiteralValue) {
                        TODO("evaluate vardecl expression $decl")
                        //RuntimeValue(decl.datatype, num = evaluate(decl.value!!, program, runtimeVariables, executeSubroutine).numericValue())
                    } else {
                        RuntimeValue.from(decl.value as LiteralValue, heap)
                    }
                }
                in StringDatatypes -> {
                    RuntimeValue.from(decl.value as LiteralValue, heap)
                }
                in ArrayDatatypes -> {
                    RuntimeValue.from(decl.value as LiteralValue, heap)
                }
                else -> throw VmExecutionException("weird type ${decl.datatype}")
            }
            runtimeVariables.define(decl.definingScope(), decl.name, value)
        }
        return super.process(decl)
    }

//    override fun process(assignment: Assignment): IStatement {
//        if(assignment is VariableInitializationAssignment) {
//            println("INIT VAR $assignment")
//        }
//        return super.process(assignment)
//    }

}
