package prog8.astvm

import prog8.ast.*
import prog8.compiler.RuntimeValue
import prog8.compiler.RuntimeValueRange
import kotlin.math.abs

fun evaluate(expr: IExpression, program: Program, runtimeVars: RuntimeVariables,
             executeSubroutine: (sub: Subroutine, args: List<RuntimeValue>) -> List<RuntimeValue>): RuntimeValue {
    val constval = expr.constValue(program)
    if(constval!=null)
        return RuntimeValue.from(constval, program.heap)

    when(expr) {
        is LiteralValue -> {
            return RuntimeValue.from(expr, program.heap)
        }
        is PrefixExpression -> {
            TODO("prefixexpr $expr")
        }
        is BinaryExpression -> {
            val left = evaluate(expr.left, program, runtimeVars, executeSubroutine)
            val right = evaluate(expr.right, program, runtimeVars, executeSubroutine)
            return when(expr.operator) {
                "<" -> RuntimeValue(DataType.UBYTE, if (left < right) 1 else 0)
                "<=" -> RuntimeValue(DataType.UBYTE, if (left <= right) 1 else 0)
                ">" -> RuntimeValue(DataType.UBYTE, if (left > right) 1 else 0)
                ">=" -> RuntimeValue(DataType.UBYTE, if (left >= right) 1 else 0)
                "==" -> RuntimeValue(DataType.UBYTE, if (left == right) 1 else 0)
                "!=" -> RuntimeValue(DataType.UBYTE, if (left != right) 1 else 0)
                "+" -> {
                    val result = left.add(right)
                    RuntimeValue(result.type, result.numericValue())
                }
                "-" -> {
                    val result = left.sub(right)
                    RuntimeValue(result.type, result.numericValue())
                }
                else -> TODO("binexpression operator ${expr.operator}")
            }
        }
        is ArrayIndexedExpression -> {
            val array = evaluate(expr.identifier, program, runtimeVars, executeSubroutine)
            val index = evaluate(expr.arrayspec.index, program, runtimeVars, executeSubroutine)
            val value = array.array!![index.integerValue()]
            return when(array.type) {
                DataType.ARRAY_UB -> RuntimeValue(DataType.UBYTE, num = value)
                DataType.ARRAY_B -> RuntimeValue(DataType.BYTE, num = value)
                DataType.ARRAY_UW -> RuntimeValue(DataType.UWORD, num = value)
                DataType.ARRAY_W -> RuntimeValue(DataType.WORD, num = value)
                DataType.ARRAY_F -> RuntimeValue(DataType.FLOAT, num = value)
                else -> throw VmExecutionException("strange array type ${array.type}")
            }
        }
        is TypecastExpression -> {
            return evaluate(expr.expression, program, runtimeVars, executeSubroutine).cast(expr.type)
        }
        is AddressOf -> {
            // we support: address of heap var -> the heap id
            val heapId = expr.identifier.heapId(program.namespace)
            return RuntimeValue(DataType.UWORD, heapId)
        }
        is DirectMemoryRead -> {
            TODO("memoryread $expr")
        }
        is DirectMemoryWrite -> {
            TODO("memorywrite $expr")
        }
        is RegisterExpr -> return runtimeVars.get(program.namespace, expr.register.name)
        is IdentifierReference -> {
            val scope = expr.definingScope()
            val variable = scope.lookup(expr.nameInSource, expr)
            if(variable is VarDecl) {
                val stmt = scope.lookup(listOf(variable.name), expr)!!
                return runtimeVars.get(stmt.definingScope(), variable.name)
            } else
                TODO("weird ref $variable")
        }
        is FunctionCall -> {
            val sub = expr.target.targetStatement(program.namespace)
            val args = expr.arglist.map { evaluate(it, program, runtimeVars, executeSubroutine) }
            when(sub) {
                is Subroutine -> {
                    val results = executeSubroutine(sub, args)
                    if(results.size!=1)
                        throw VmExecutionException("expected 1 result from functioncall $expr")
                    return results[0]
                }
                else -> {
                    TODO("call expr function ${expr.target}")
                }
            }
        }
        is RangeExpr -> {
            val cRange = expr.toConstantIntegerRange(program.heap)
            if(cRange!=null)
                return RuntimeValueRange(expr.resultingDatatype(program)!!, cRange)
            val fromVal = evaluate(expr.from, program, runtimeVars, executeSubroutine).integerValue()
            val toVal = evaluate(expr.to, program, runtimeVars, executeSubroutine).integerValue()
            val stepVal = evaluate(expr.step, program, runtimeVars, executeSubroutine).integerValue()
            val range = when {
                fromVal <= toVal -> when {
                    stepVal <= 0 -> IntRange.EMPTY
                    stepVal == 1 -> fromVal..toVal
                    else -> fromVal..toVal step stepVal
                }
                else -> when {
                    stepVal >= 0 -> IntRange.EMPTY
                    stepVal == -1 -> fromVal downTo toVal
                    else -> fromVal downTo toVal step abs(stepVal)
                }
            }
            return RuntimeValueRange(expr.resultingDatatype(program)!!, range)
        }
        else -> {
            TODO("implement eval $expr")
        }
    }
}
