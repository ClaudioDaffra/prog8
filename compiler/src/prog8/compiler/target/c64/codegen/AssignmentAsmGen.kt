package prog8.compiler.target.c64.codegen

import prog8.ast.Program
import prog8.ast.base.*
import prog8.ast.expressions.*
import prog8.ast.statements.AssignTarget
import prog8.ast.statements.Assignment
import prog8.ast.statements.DirectMemoryWrite
import prog8.ast.statements.VarDecl
import prog8.compiler.AssemblyError
import prog8.compiler.target.c64.C64MachineDefinition
import prog8.compiler.target.c64.C64MachineDefinition.C64Zeropage
import prog8.compiler.target.c64.C64MachineDefinition.ESTACK_HI_HEX
import prog8.compiler.target.c64.C64MachineDefinition.ESTACK_LO_HEX
import prog8.compiler.toHex


internal class AssignmentAsmGen(private val program: Program, private val errors: ErrorReporter, private val asmgen: AsmGen) {

    internal fun translate(assign: Assignment) {
        if (assign.aug_op == null)
            translateNormalAssignment(assign)
        else
            translateInplaceAssignment(assign)
    }

    private fun translateInplaceAssignment(assign: Assignment) {
        require(assign.aug_op != null)

        when {
            assign.target.register != null -> {
                if (inplaceAssignToRegister(assign))
                    return
            }
            assign.target.identifier != null -> {
                if (inplaceAssignToIdentifier(assign))
                    return
            }
            assign.target.memoryAddress != null -> {
                if (inplaceAssignToMemoryByte(assign))
                    return
            }
            assign.target.arrayindexed != null -> {
                if (inplaceAssignToArrayOrString(assign))
                    return
            }
        }

        // TODO this is the FALLBACK:
        errors.warn("using suboptimal in-place assignment code (this should still be optimized)", assign.position)
        val normalAssignment = assign.asDesugaredNonaugmented()
        return translateNormalAssignment(normalAssignment)
    }

    private fun inplaceAssignToArrayOrString(assign: Assignment): Boolean {
        val targetArray = assign.target.arrayindexed!!
        val arrayName = targetArray.identifier
        val arrayIndex = targetArray.arrayspec.index
        val targetName = asmgen.asmIdentifierName(arrayName)
        val arrayDt = arrayName.targetVarDecl(program.namespace)!!.datatype
        val constValue = assign.value.constValue(program)?.number
        if (constValue != null) {
            // constant value to set in array
            val hexValue = constValue.toHex()
            if (assign.aug_op == "setvalue") {
                when (arrayDt) {
                    DataType.ARRAY_B, DataType.ARRAY_UB, DataType.STR -> {
                        if (arrayIndex is NumericLiteralValue)
                            asmgen.out(" ldy  #${arrayIndex.number.toHex()}")
                        else
                            asmgen.translateArrayIndexIntoY(targetArray)
                        asmgen.out(" lda  #$hexValue |  sta  $targetName,y")
                    }
                    DataType.ARRAY_W, DataType.ARRAY_UW -> {
                        if (arrayIndex is NumericLiteralValue)
                            asmgen.out(" lda  #${arrayIndex.number.toHex()}")
                        else
                            asmgen.translateArrayIndexIntoA(targetArray)
                        asmgen.out("""
                        asl  a
                        tay
                        lda  #<$hexValue
                        sta  $targetName,y
                        lda  #>$hexValue
                        sta  $targetName+1,y
                    """)
                    }
                    DataType.ARRAY_F -> {
                        assignFromFloatConstant(assign.target, constValue.toDouble())
                    }
                    else -> throw AssemblyError("invalid array dt $arrayDt")
                }
            } else {
                TODO()
            }
            return true
        }

        // non-const value.
        // !!! DON'T FORGET :  CAN BE AUGMENTED ASSIGNMENT !!!
        when (assign.value) {
            is RegisterExpr -> {
                TODO()
            }
            is IdentifierReference -> {
                val sourceName = asmgen.asmIdentifierName(assign.value as IdentifierReference)
                TODO("$assign")
            }
            is AddressOf -> {
                TODO("$assign")
            }
            is DirectMemoryRead -> {
                TODO("$assign")
            }
            is ArrayIndexedExpression -> {
                TODO("$assign")
            }
            else -> {
                fallbackAssignment(assign)
                return true
            }
        }

        return false
    }

    private fun inplaceAssignToMemoryByte(assign: Assignment): Boolean {
        val address = assign.target.memoryAddress?.addressExpression?.constValue(program)?.number ?: return false
        val hexAddr = address.toHex()
        val constValue = assign.value.constValue(program)
        if (constValue != null) {
            val hexValue = constValue.number.toHex()
            when (assign.aug_op) {
                "setvalue" -> asmgen.out(" lda  #$hexValue |  sta  $hexAddr")
                "+=" -> asmgen.out(" lda  $hexAddr |  clc |  adc  #$hexValue |  sta  $hexAddr")
                "-=" -> asmgen.out(" lda  $hexAddr |  sec |  sbc  #$hexValue |  sta  $hexAddr")
                "/=" -> TODO("/=")
                "*=" -> TODO("*=")
                "**=" -> TODO("**=")
                "&=" -> asmgen.out(" lda  $hexAddr |  and  #$hexValue |  sta  $hexAddr")
                "|=" -> asmgen.out(" lda  $hexAddr |  ora  #$hexValue |  sta  $hexAddr")
                "^=" -> asmgen.out(" lda  $hexAddr |  eor  #$hexValue |  sta  $hexAddr")
                "%=" -> TODO("%=")
                "<<=" -> throw AssemblyError("<<= should have been replaced by lsl()")
                ">>=" -> throw AssemblyError("<<= should have been replaced by lsr()")
                else -> throw AssemblyError("invalid aug_op ${assign.aug_op}")
            }
            return true
        }

        // non-const value.
        when (assign.value) {
            is RegisterExpr -> {
                when (assign.aug_op) {
                    "setvalue" -> {
                        when ((assign.value as RegisterExpr).register) {
                            Register.A -> asmgen.out(" sta  $hexAddr")
                            Register.X -> asmgen.out(" stx  $hexAddr")
                            Register.Y -> asmgen.out(" sty  $hexAddr")
                        }
                    }
                    "+=" -> {
                        when ((assign.value as RegisterExpr).register) {
                            Register.A -> asmgen.out(" clc |  adc  $hexAddr |  sta  $hexAddr")
                            Register.X -> asmgen.out(" txa |  clc |  adc  $hexAddr |  sta  $hexAddr")
                            Register.Y -> asmgen.out(" tya |  clc |  adc  $hexAddr |  sta  $hexAddr")
                        }
                    }
                    "-=" -> {
                        when ((assign.value as RegisterExpr).register) {
                            Register.A -> asmgen.out(" sta  ${C64Zeropage.SCRATCH_B1} | lda  $hexAddr |  sec |  sbc  ${C64Zeropage.SCRATCH_B1} |  sta  $hexAddr")
                            Register.X -> asmgen.out(" stx  ${C64Zeropage.SCRATCH_B1} | lda  $hexAddr |  sec |  sbc  ${C64Zeropage.SCRATCH_B1} |  sta  $hexAddr")
                            Register.Y -> asmgen.out(" sty  ${C64Zeropage.SCRATCH_B1} | lda  $hexAddr |  sec |  sbc  ${C64Zeropage.SCRATCH_B1} |  sta  $hexAddr")
                        }
                    }
                    "/=" -> TODO("/=")
                    "*=" -> TODO("*=")
                    "**=" -> TODO("**=")
                    "&=" -> {
                        when ((assign.value as RegisterExpr).register) {
                            Register.A -> asmgen.out(" and  $hexAddr |  sta  $hexAddr")
                            Register.X -> asmgen.out(" txa |  and  $hexAddr |  sta  $hexAddr")
                            Register.Y -> asmgen.out(" tya |  and  $hexAddr |  sta  $hexAddr")
                        }
                    }
                    "|=" -> {
                        when ((assign.value as RegisterExpr).register) {
                            Register.A -> asmgen.out(" ora  $hexAddr |  sta  $hexAddr")
                            Register.X -> asmgen.out(" txa |  ora  $hexAddr |  sta  $hexAddr")
                            Register.Y -> asmgen.out(" tya |  ora  $hexAddr |  sta  $hexAddr")
                        }
                    }
                    "^=" -> {
                        when ((assign.value as RegisterExpr).register) {
                            Register.A -> asmgen.out(" eor  $hexAddr |  sta  $hexAddr")
                            Register.X -> asmgen.out(" txa |  eor  $hexAddr |  sta  $hexAddr")
                            Register.Y -> asmgen.out(" tya |  eor  $hexAddr |  sta  $hexAddr")
                        }
                    }
                    "%=" -> TODO("%=")
                    "<<=" -> throw AssemblyError("<<= should have been replaced by lsl()")
                    ">>=" -> throw AssemblyError("<<= should have been replaced by lsr()")
                    else -> throw AssemblyError("invalid aug_op ${assign.aug_op}")
                }
                return true
            }
            is IdentifierReference -> {
                val sourceName = asmgen.asmIdentifierName(assign.value as IdentifierReference)
                TODO("$assign")
            }
            is AddressOf -> {
                TODO("$assign")
            }
            is DirectMemoryRead -> {
                TODO("$assign")
            }
            is ArrayIndexedExpression -> {
                TODO("$assign")
            }
            else -> {
                fallbackAssignment(assign)
                return true
            }
        }

        return false
    }

    private fun inplaceAssignToIdentifier(assign: Assignment): Boolean {
        val targetType = assign.target.inferType(program, assign)
        val constNumber = assign.value.constValue(program)?.number
        val targetName = asmgen.asmIdentifierName(assign.target.identifier!!)

        when (targetType.typeOrElse(DataType.STRUCT)) {
            DataType.UBYTE, DataType.BYTE -> {
                // (u)byte assignment
                if (constNumber != null) {
                    val hexValue = constNumber.toHex()
                    when (assign.aug_op) {
                        "setvalue" -> asmgen.out(" lda  #$hexValue |  sta  $targetName")
                        "+=" -> asmgen.out(" lda  $targetName |  clc |  adc  #$hexValue |  sta  $targetName")
                        "-=" -> asmgen.out(" lda  $targetName |  sec |  sbc  #$hexValue |  sta  $targetName")
                        "/=" -> TODO("/=")
                        "*=" -> TODO("*=")
                        "**=" -> TODO("**=")
                        "&=" -> asmgen.out(" lda  $targetName |  and  #$hexValue |  sta  $targetName")
                        "|=" -> asmgen.out(" lda  $targetName |  ora  #$hexValue |  sta  $targetName")
                        "^=" -> asmgen.out(" lda  $targetName |  eor  #$hexValue |  sta  $targetName")
                        "%=" -> TODO("%=")
                        "<<=" -> throw AssemblyError("<<= should have been replaced by lsl()")
                        ">>=" -> throw AssemblyError("<<= should have been replaced by lsr()")
                        else -> throw AssemblyError("invalid aug_op ${assign.aug_op}")
                    }
                    return true
                }

                // non-const (u)byte value
                // !!! DON'T FORGET :  CAN BE AUGMENTED ASSIGNMENT !!!
                when (assign.value) {
                    is RegisterExpr -> {
                        TODO("$assign")
                    }
                    is IdentifierReference -> {
                        val sourceName = asmgen.asmIdentifierName(assign.value as IdentifierReference)
                        when (assign.aug_op) {
                            "setvalue" -> asmgen.out(" lda  $sourceName |  sta  $targetName")
                            "+=" -> asmgen.out(" lda  $targetName |  clc |  adc  $sourceName |  sta  $targetName")
                            "-=" -> asmgen.out(" lda  $targetName |  sec |  sbc  $sourceName |  sta  $targetName")
                            "/=" -> TODO("/=")
                            "*=" -> TODO("*=")
                            "**=" -> TODO("**=")
                            "&=" -> asmgen.out(" lda  $targetName |  and  $sourceName |  sta  $targetName")
                            "|=" -> asmgen.out(" lda  $targetName |  ora  $sourceName |  sta  $targetName")
                            "^=" -> asmgen.out(" lda  $targetName |  eor  $sourceName |  sta  $targetName")
                            "%=" -> TODO("%=")
                            "<<=" -> throw AssemblyError("<<= should have been replaced by lsl()")
                            ">>=" -> throw AssemblyError("<<= should have been replaced by lsr()")
                            else -> throw AssemblyError("invalid aug_op ${assign.aug_op}")
                        }
                        return true
                    }
                    is DirectMemoryRead -> {
                        TODO("$assign")
                    }
                    is ArrayIndexedExpression -> {
                        if (assign.aug_op == "setvalue") {
                            val arrayExpr = assign.value as ArrayIndexedExpression
                            val arrayDt = arrayExpr.identifier.inferType(program).typeOrElse(DataType.STRUCT)
                            if (arrayDt != DataType.ARRAY_B && arrayDt != DataType.ARRAY_UB)
                                throw AssemblyError("expected byte array")
                            val arrayIndex = arrayExpr.arrayspec.index
                            val variablename = asmgen.asmIdentifierName(arrayExpr.identifier)
                            if (arrayIndex is NumericLiteralValue)
                                asmgen.out(" ldy  #${arrayIndex.number.toHex()}")
                            else
                                asmgen.translateArrayIndexIntoY(arrayExpr)
                            asmgen.out("""
                            lda  $variablename,y
                            sta  $targetName
                            lda  $variablename+1,y
                            sta  $targetName+1
                        """)
                        } else {
                            // TODO optimize more augmented assignment cases
                            val normalAssign = assign.asDesugaredNonaugmented()
                            asmgen.translateExpression(normalAssign.value)
                            assignFromEvalResult(normalAssign.target)
                        }
                        return true
                    }
                    else -> {
                        fallbackAssignment(assign)
                        return true
                    }
                }
            }
            DataType.UWORD, DataType.WORD -> {
                if (constNumber != null) {
                    val hexNumber = constNumber.toHex()
                    when (assign.aug_op) {
                        "setvalue" -> {
                            asmgen.out("""
                            lda  #<$hexNumber
                            sta  $targetName
                            lda  #>$hexNumber
                            sta  $targetName+1
                        """)
                        }
                        "+=" -> {
                            asmgen.out("""
                            lda  $targetName
                            clc
                            adc  #<$hexNumber
                            sta  $targetName
                            lda  $targetName+1
                            adc  #>$hexNumber
                            sta  $targetName+1
                        """)
                            return true
                        }
                        "-=" -> {
                            return false // TODO("optimized word -= const")
                        }
                        else -> TODO("$assign")
                    }
                    return true
                }

                // non-const value
                // !!! DON'T FORGET :  CAN BE AUGMENTED ASSIGNMENT !!!
                when (assign.value) {
                    is RegisterExpr -> {
                        TODO("$assign")
                    }
                    is IdentifierReference -> {
                        val sourceName = asmgen.asmIdentifierName(assign.value as IdentifierReference)
                        when (assign.aug_op) {
                            "setvalue" -> {
                                asmgen.out("""
                                    lda  $sourceName
                                    sta  $targetName
                                    lda  $sourceName+1
                                    sta  $targetName+1
                                """)
                            }
                            "+=" -> {
                                asmgen.out("""
                                    lda  $targetName
                                    clc
                                    adc  $sourceName
                                    sta  $targetName
                                    lda  $targetName+1
                                    adc  $sourceName+1
                                    sta  $targetName+1
                                """)
                                return true
                            }
                            "-=" -> {
                                asmgen.out("""
                                    lda  $targetName
                                    sec
                                    sbc  $sourceName
                                    sta  $targetName
                                    lda  $targetName+1
                                    sbc  $sourceName+1
                                    sta  $targetName+1
                                """)
                                return true
                            }
                            else -> {
                                TODO("$assign")
                            }
                        }
                        return true
                    }
                    is DirectMemoryRead -> {
                        TODO("$assign")
                    }
                    is AddressOf -> {
                        TODO("$assign")
                    }
                    is ArrayIndexedExpression -> {
                        if (assign.aug_op == "setvalue") {
                            val arrayExpr = assign.value as ArrayIndexedExpression
                            val arrayDt = arrayExpr.identifier.inferType(program).typeOrElse(DataType.STRUCT)
                            if (arrayDt != DataType.ARRAY_W && arrayDt != DataType.ARRAY_UW)
                                throw AssemblyError("expected word array")
                            val arrayIndex = arrayExpr.arrayspec.index
                            val variablename = asmgen.asmIdentifierName(arrayExpr.identifier)
                            if (arrayIndex is NumericLiteralValue)
                                asmgen.out(" lda  #${arrayIndex.number.toHex()}")
                            else
                                asmgen.translateArrayIndexIntoA(arrayExpr)
                            asmgen.out("""
                        asl  a
                        tay
                        lda  $variablename,y
                        sta  $targetName
                        lda  $variablename+1,y
                        sta  $targetName+1
                    """)
                        } else {
                            // TODO optimize more augmented assignment cases
                            val normalAssign = assign.asDesugaredNonaugmented()
                            asmgen.translateExpression(normalAssign.value)
                            assignFromEvalResult(normalAssign.target)
                        }
                        return true
                    }
                    else -> {
                        fallbackAssignment(assign)
                        return true
                    }
                }
            }
            DataType.FLOAT -> {
                if (constNumber != null) {
                    // assign a constant
                    val floatConst = asmgen.getFloatConst(constNumber.toDouble())
                    when (assign.aug_op) {
                        "setvalue" -> assignFromFloatConstant(assign.target, constNumber.toDouble())
                        "+=" -> {
                            if (constNumber == 0.5) {
                                asmgen.out("""
                                lda  #<$targetName
                                ldy  #>$targetName
                                jsr  c64flt.MOVFM
                                jsr  c64flt.FADDH
                                stx  c64.SCRATCH_ZPREGX
                                ldx  #<$targetName
                                ldy  #>$targetName
                                jsr  c64flt.MOVMF
                                ldx  c64.SCRATCH_ZPREGX
                            """)
                            } else {
                                asmgen.out("""
                                lda  #<$targetName
                                ldy  #>$targetName
                                jsr  c64flt.MOVFM
                                lda  #<$floatConst
                                ldy  #>$floatConst
                                jsr  c64flt.FADD
                                stx  c64.SCRATCH_ZPREGX
                                ldx  #<$targetName
                                ldy  #>$targetName
                                jsr  c64flt.MOVMF
                                ldx  c64.SCRATCH_ZPREGX
                            """)
                            }
                            return true
                        }
                        "-=" -> {
                            asmgen.out("""
                            lda  #<$floatConst
                            ldy  #>$floatConst
                            jsr  c64flt.MOVFM
                            lda  #<$targetName
                            ldy  #>$targetName
                            jsr  c64flt.FSUB
                            stx  c64.SCRATCH_ZPREGX
                            ldx  #<$targetName
                            ldy  #>$targetName
                            jsr  c64flt.MOVMF
                            ldx  c64.SCRATCH_ZPREGX
                        """)
                            return true
                        }
                        else -> TODO("$assign")
                    }
                    return true
                }

                // non-const float value.
                // !!! DON'T FORGET :  CAN BE AUGMENTED ASSIGNMENT !!!
                when (assign.value) {
                    is IdentifierReference -> {
                        when (assign.aug_op) {
                            "setvalue" -> assignFromFloatVariable(assign.target, assign.value as IdentifierReference)
                            "+=" -> return false        // TODO optimized float += variable
                            "-=" -> return false        // TODO optimized float -= variable
                            else -> TODO("$assign")
                        }
                        return true
                    }
                    is ArrayIndexedExpression -> {
                        TODO("$assign")
                    }
                    else -> {
                        fallbackAssignment(assign)
                        return true
                    }
                }
            }
            else -> throw AssemblyError("invalid dt")
        }
        return false
    }

    private fun inplaceAssignToRegister(assign: Assignment): Boolean {
        val constValue = assign.value.constValue(program)
        if (constValue != null) {
            val hexValue = constValue.number.toHex()
            when (assign.target.register) {
                Register.A -> {
                    when (assign.aug_op) {
                        "setvalue" -> asmgen.out(" lda  #$hexValue")
                        "+=" -> asmgen.out(" clc |  adc  #$hexValue")
                        "-=" -> asmgen.out(" sec |  sbc  #$hexValue")
                        "/=" -> TODO("/=")
                        "*=" -> TODO("*=")
                        "**=" -> TODO("**=")
                        "&=" -> asmgen.out(" and  #$hexValue")
                        "|=" -> asmgen.out(" ora  #$hexValue")
                        "^=" -> asmgen.out(" eor  #$hexValue")
                        "%=" -> TODO("%=")
                        "<<=" -> throw AssemblyError("<<= should have been replaced by lsl()")
                        ">>=" -> throw AssemblyError("<<= should have been replaced by lsr()")
                        else -> throw AssemblyError("invalid aug_op ${assign.aug_op}")
                    }
                }
                Register.X -> {
                    when (assign.aug_op) {
                        "setvalue" -> asmgen.out(" ldx  #$hexValue")
                        "+=" -> asmgen.out(" txa |  clc |  adc  #$hexValue |  tax")
                        "-=" -> asmgen.out(" txa |  sec |  sbc  #$hexValue |  tax")
                        "/=" -> TODO("/=")
                        "*=" -> TODO("*=")
                        "**=" -> TODO("**=")
                        "&=" -> asmgen.out(" txa |  and  #$hexValue |  tax")
                        "|=" -> asmgen.out(" txa |  ora  #$hexValue |  tax")
                        "^=" -> asmgen.out(" txa |  eor  #$hexValue |  tax")
                        "%=" -> TODO("%=")
                        "<<=" -> throw AssemblyError("<<= should have been replaced by lsl()")
                        ">>=" -> throw AssemblyError("<<= should have been replaced by lsr()")
                        else -> throw AssemblyError("invalid aug_op ${assign.aug_op}")
                    }
                }
                Register.Y -> {
                    when (assign.aug_op) {
                        "setvalue" -> asmgen.out(" ldy  #$hexValue")
                        "+=" -> asmgen.out(" tya |  clc |  adc  #$hexValue |  tay")
                        "-=" -> asmgen.out(" tya |  sec |  sbc  #$hexValue |  tay")
                        "/=" -> TODO("/=")
                        "*=" -> TODO("*=")
                        "**=" -> TODO("**=")
                        "&=" -> asmgen.out(" tya |  and  #$hexValue |  tay")
                        "|=" -> asmgen.out(" tya |  ora  #$hexValue |  tay")
                        "^=" -> asmgen.out(" tya |  eor  #$hexValue |  tay")
                        "%=" -> TODO("%=")
                        "<<=" -> throw AssemblyError("<<= should have been replaced by lsl()")
                        ">>=" -> throw AssemblyError("<<= should have been replaced by lsr()")
                        else -> throw AssemblyError("invalid aug_op ${assign.aug_op}")
                    }
                }
            }
            return true
        }

        // non-const value.
        when (assign.value) {
            is IdentifierReference -> {
                val sourceName = asmgen.asmIdentifierName(assign.value as IdentifierReference)
                when (assign.target.register) {
                    Register.A -> {
                        when (assign.aug_op) {
                            "setvalue" -> asmgen.out(" lda  $sourceName")
                            "+=" -> asmgen.out(" clc |  adc  $sourceName")
                            "-=" -> asmgen.out(" sec |  sbc  $sourceName")
                            "/=" -> TODO("/=")
                            "*=" -> TODO("*=")
                            "**=" -> TODO("**=")
                            "&=" -> asmgen.out(" and  $sourceName")
                            "|=" -> asmgen.out(" ora  $sourceName")
                            "^=" -> asmgen.out(" eor  $sourceName")
                            "%=" -> TODO("%=")
                            "<<=" -> throw AssemblyError("<<= should have been replaced by lsl()")
                            ">>=" -> throw AssemblyError("<<= should have been replaced by lsr()")
                            else -> throw AssemblyError("invalid aug_op ${assign.aug_op}")
                        }
                    }
                    Register.X -> {
                        when (assign.aug_op) {
                            "setvalue" -> asmgen.out(" ldx  $sourceName")
                            "+=" -> asmgen.out(" txa |  clc |  adc  $sourceName |  tax")
                            "-=" -> asmgen.out(" txa |  sec |  sbc  $sourceName |  tax")
                            "/=" -> TODO("/=")
                            "*=" -> TODO("*=")
                            "**=" -> TODO("**=")
                            "&=" -> asmgen.out(" txa |  and  $sourceName |  tax")
                            "|=" -> asmgen.out(" txa |  ora  $sourceName |  tax")
                            "^=" -> asmgen.out(" txa |  eor  $sourceName |  tax")
                            "%=" -> TODO("%=")
                            "<<=" -> throw AssemblyError("<<= should have been replaced by lsl()")
                            ">>=" -> throw AssemblyError("<<= should have been replaced by lsr()")
                            else -> throw AssemblyError("invalid aug_op ${assign.aug_op}")
                        }
                    }
                    Register.Y -> {
                        when (assign.aug_op) {
                            "setvalue" -> asmgen.out(" ldy  $sourceName")
                            "+=" -> asmgen.out(" tya |  clc |  adc  $sourceName |  tay")
                            "-=" -> asmgen.out(" tya |  sec |  sbc  $sourceName |  tay")
                            "/=" -> TODO("/=")
                            "*=" -> TODO("*=")
                            "**=" -> TODO("**=")
                            "&=" -> asmgen.out(" tya |  and  $sourceName |  tay")
                            "|=" -> asmgen.out(" tya |  ora  $sourceName |  tay")
                            "^=" -> asmgen.out(" tya |  eor  $sourceName |  tay")
                            "%=" -> TODO("%=")
                            "<<=" -> throw AssemblyError("<<= should have been replaced by lsl()")
                            ">>=" -> throw AssemblyError("<<= should have been replaced by lsr()")
                            else -> throw AssemblyError("invalid aug_op ${assign.aug_op}")
                        }
                    }
                }
                return true
            }
            is RegisterExpr -> {
                when (assign.aug_op) {
                    "setvalue" -> {
                        when ((assign.value as RegisterExpr).register) {
                            Register.A -> {
                                when (assign.target.register!!) {
                                    Register.A -> {
                                    }
                                    Register.X -> asmgen.out("  tax")
                                    Register.Y -> asmgen.out("  tay")
                                }
                            }
                            Register.X -> {
                                when (assign.target.register!!) {
                                    Register.A -> asmgen.out("  txa")
                                    Register.X -> {
                                    }
                                    Register.Y -> asmgen.out("  txa |  tay")
                                }
                            }
                            Register.Y -> {
                                when (assign.target.register!!) {
                                    Register.A -> asmgen.out("  tya")
                                    Register.X -> asmgen.out("  tya |  tax")
                                    Register.Y -> {
                                    }
                                }
                            }
                        }
                    }
                    "+=" -> {
                        when ((assign.value as RegisterExpr).register) {
                            Register.A -> {
                                when (assign.target.register!!) {
                                    Register.A -> throw AssemblyError("should have been optimized away")
                                    Register.X -> asmgen.out(" stx  ${C64Zeropage.SCRATCH_B1} |  clc |  adc  ${C64Zeropage.SCRATCH_B1} |  tax")
                                    Register.Y -> asmgen.out(" sty  ${C64Zeropage.SCRATCH_B1} |  clc |  adc  ${C64Zeropage.SCRATCH_B1} |  tay")
                                }
                            }
                            Register.X -> {
                                when (assign.target.register!!) {
                                    Register.A -> asmgen.out(" stx  ${C64Zeropage.SCRATCH_B1} |  clc |  adc  ${C64Zeropage.SCRATCH_B1}")
                                    Register.X -> throw AssemblyError("should have been optimized away")
                                    Register.Y -> asmgen.out(" stx  ${C64Zeropage.SCRATCH_B1} |  tya |  clc |  adc  ${C64Zeropage.SCRATCH_B1} |  tay")
                                }
                            }
                            Register.Y -> {
                                when (assign.target.register!!) {
                                    Register.A -> asmgen.out("  sty  ${C64Zeropage.SCRATCH_B1} |  clc |  adc  ${C64Zeropage.SCRATCH_B1}")
                                    Register.X -> asmgen.out("  sty  ${C64Zeropage.SCRATCH_B1} |  txa |  clc |  adc  ${C64Zeropage.SCRATCH_B1} |  tax")
                                    Register.Y -> throw AssemblyError("should have been optimized away")
                                }
                            }
                        }
                    }
                    "-=" -> {
                        when ((assign.value as RegisterExpr).register) {
                            Register.A -> {
                                when (assign.target.register!!) {
                                    Register.A -> throw AssemblyError("should have been optimized away")
                                    Register.X -> asmgen.out(" stx  ${C64Zeropage.SCRATCH_B1} |  sec |  sbc  ${C64Zeropage.SCRATCH_B1} |  tax")
                                    Register.Y -> asmgen.out(" sty  ${C64Zeropage.SCRATCH_B1} |  sec |  sbc  ${C64Zeropage.SCRATCH_B1} |  tay")
                                }
                            }
                            Register.X -> {
                                when (assign.target.register!!) {
                                    Register.A -> asmgen.out(" stx  ${C64Zeropage.SCRATCH_B1} |  sec |  sbc  ${C64Zeropage.SCRATCH_B1}")
                                    Register.X -> throw AssemblyError("should have been optimized away")
                                    Register.Y -> asmgen.out(" stx  ${C64Zeropage.SCRATCH_B1} |  tya |  sec |  sbc  ${C64Zeropage.SCRATCH_B1} |  tay")
                                }
                            }
                            Register.Y -> {
                                when (assign.target.register!!) {
                                    Register.A -> asmgen.out("  sty  ${C64Zeropage.SCRATCH_B1} |  sec |  sbc  ${C64Zeropage.SCRATCH_B1}")
                                    Register.X -> asmgen.out("  sty  ${C64Zeropage.SCRATCH_B1} |  txa |  sec |  sbc  ${C64Zeropage.SCRATCH_B1} |  tax")
                                    Register.Y -> throw AssemblyError("should have been optimized away")
                                }
                            }
                        }
                    }
                    "/=" -> TODO("/=")
                    "*=" -> TODO("*=")
                    "**=" -> TODO("**=")
                    "&=" -> {
                        when ((assign.value as RegisterExpr).register) {
                            Register.A -> {
                                when (assign.target.register!!) {
                                    Register.A -> throw AssemblyError("should have been optimized away")
                                    Register.X -> asmgen.out(" stx  ${C64Zeropage.SCRATCH_B1} |  and  ${C64Zeropage.SCRATCH_B1} |  tax")
                                    Register.Y -> asmgen.out(" sty  ${C64Zeropage.SCRATCH_B1} |  and  ${C64Zeropage.SCRATCH_B1} |  tay")
                                }
                            }
                            Register.X -> {
                                when (assign.target.register!!) {
                                    Register.A -> asmgen.out(" stx  ${C64Zeropage.SCRATCH_B1} |  and  ${C64Zeropage.SCRATCH_B1}")
                                    Register.X -> throw AssemblyError("should have been optimized away")
                                    Register.Y -> asmgen.out(" stx  ${C64Zeropage.SCRATCH_B1} |  tya |  and  ${C64Zeropage.SCRATCH_B1} |  tay")
                                }
                            }
                            Register.Y -> {
                                when (assign.target.register!!) {
                                    Register.A -> asmgen.out(" sty  ${C64Zeropage.SCRATCH_B1} |  and  ${C64Zeropage.SCRATCH_B1}")
                                    Register.X -> asmgen.out(" stx  ${C64Zeropage.SCRATCH_B1} |  tya |  and  ${C64Zeropage.SCRATCH_B1} |  tax")
                                    Register.Y -> throw AssemblyError("should have been optimized away")
                                }
                            }
                        }
                    }
                    "|=" -> TODO()
                    "^=" -> TODO()
                    "%=" -> TODO("%=")
                    "<<=" -> throw AssemblyError("<<= should have been replaced by lsl()")
                    ">>=" -> throw AssemblyError("<<= should have been replaced by lsr()")
                    else -> throw AssemblyError("invalid aug_op ${assign.aug_op}")
                }
                return true
            }
            is DirectMemoryRead -> {
                val address = (assign.value as DirectMemoryRead).addressExpression.constValue(program)?.number
                if (address != null) {
                    val hexAddr = address.toHex()
                    when (assign.target.register) {
                        Register.A -> {
                            when (assign.aug_op) {
                                "setvalue" -> asmgen.out(" lda  $hexAddr")
                                "+=" -> asmgen.out(" clc |  adc  $hexAddr")
                                "-=" -> asmgen.out(" sec |  sbc  $hexAddr")
                                "/=" -> TODO("/=")
                                "*=" -> TODO("*=")
                                "**=" -> TODO("**=")
                                "&=" -> asmgen.out(" and  $hexAddr")
                                "|=" -> asmgen.out(" ora  $hexAddr")
                                "^=" -> asmgen.out(" eor  $hexAddr")
                                "%=" -> TODO("%=")
                                "<<=" -> throw AssemblyError("<<= should have been replaced by lsl()")
                                ">>=" -> throw AssemblyError("<<= should have been replaced by lsr()")
                                else -> throw AssemblyError("invalid aug_op ${assign.aug_op}")
                            }
                        }
                        Register.X -> {
                            when (assign.aug_op) {
                                "setvalue" -> asmgen.out(" ldx  $hexAddr")
                                "+=" -> asmgen.out(" txa |  clc |  adc  $hexAddr |  tax")
                                "-=" -> asmgen.out(" txa |  sec |  sbc  $hexAddr |  tax")
                                "/=" -> TODO("/=")
                                "*=" -> TODO("*=")
                                "**=" -> TODO("**=")
                                "&=" -> asmgen.out(" txa |  and  $hexAddr |  tax")
                                "|=" -> asmgen.out(" txa |  ora  $hexAddr |  tax")
                                "^=" -> asmgen.out(" txa |  eor  $hexAddr |  tax")
                                "%=" -> TODO("%=")
                                "<<=" -> throw AssemblyError("<<= should have been replaced by lsl()")
                                ">>=" -> throw AssemblyError("<<= should have been replaced by lsr()")
                                else -> throw AssemblyError("invalid aug_op ${assign.aug_op}")
                            }
                        }
                        Register.Y -> {
                            when (assign.aug_op) {
                                "setvalue" -> asmgen.out(" ldy  $hexAddr")
                                "+=" -> asmgen.out(" tya |  clc |  adc  $hexAddr |  tay")
                                "-=" -> asmgen.out(" tya |  sec |  sbc  $hexAddr |  tay")
                                "/=" -> TODO("/=")
                                "*=" -> TODO("*=")
                                "**=" -> TODO("**=")
                                "&=" -> asmgen.out(" tya |  and  $hexAddr |  tay")
                                "|=" -> asmgen.out(" tya |  ora  $hexAddr |  tay")
                                "^=" -> asmgen.out(" tya |  eor  $hexAddr |  tay")
                                "%=" -> TODO("%=")
                                "<<=" -> throw AssemblyError("<<= should have been replaced by lsl()")
                                ">>=" -> throw AssemblyError("<<= should have been replaced by lsr()")
                                else -> throw AssemblyError("invalid aug_op ${assign.aug_op}")
                            }
                        }
                    }
                    return true
                }
            }
            is AddressOf -> {
                TODO("$assign")
            }
            is ArrayIndexedExpression -> {
                TODO("$assign")
            }
            else -> {
                fallbackAssignment(assign)
                return true
            }
        }

        return false
    }

    private fun fallbackAssignment(assign: Assignment) {
        if (assign.aug_op != "setvalue") {
            /* stack-based evaluation of the expression is required */
            val normalAssign = assign.asDesugaredNonaugmented()
            asmgen.translateExpression(normalAssign.value)
            assignFromEvalResult(normalAssign.target)
        } else {
            when (assign.value) {
                is FunctionCall -> {
                    // TODO is there a way to avoid function return value being passed via the stack?
                    //      for instance, 1 byte return value always in A, etc
                    val normalAssign = assign.asDesugaredNonaugmented()
                    asmgen.translateExpression(normalAssign.value)
                    assignFromEvalResult(normalAssign.target)
                }
                else -> {
                    /* stack-based evaluation of the expression is required */
                    val normalAssign = assign.asDesugaredNonaugmented()
                    asmgen.translateExpression(normalAssign.value)
                    assignFromEvalResult(normalAssign.target)
                }
            }
        }
    }


    //  old code-generation below:
    //  eventually, all of this should have been replaced by newer more optimized code above.

    private fun translateNormalAssignment(assign: Assignment) {
        require(assign.aug_op == null)

        when (assign.value) {
            is NumericLiteralValue -> {
                val numVal = assign.value as NumericLiteralValue
                when (numVal.type) {
                    DataType.UBYTE, DataType.BYTE -> assignFromByteConstant(assign.target, numVal.number.toShort())
                    DataType.UWORD, DataType.WORD -> assignFromWordConstant(assign.target, numVal.number.toInt())
                    DataType.FLOAT -> assignFromFloatConstant(assign.target, numVal.number.toDouble())
                    else -> throw AssemblyError("weird numval type")
                }
            }
            is RegisterExpr -> {
                assignFromRegister(assign.target, (assign.value as RegisterExpr).register)
            }
            is IdentifierReference -> {
                val type = assign.target.inferType(program, assign).typeOrElse(DataType.STRUCT)
                when (type) {
                    DataType.UBYTE, DataType.BYTE -> assignFromByteVariable(assign.target, assign.value as IdentifierReference)
                    DataType.UWORD, DataType.WORD -> assignFromWordVariable(assign.target, assign.value as IdentifierReference)
                    DataType.FLOAT -> assignFromFloatVariable(assign.target, assign.value as IdentifierReference)
                    else -> throw AssemblyError("unsupported assignment target type $type")
                }
            }
            is AddressOf -> {
                val identifier = (assign.value as AddressOf).identifier
                assignFromAddressOf(assign.target, identifier)
            }
            is DirectMemoryRead -> {
                val read = (assign.value as DirectMemoryRead)
                when (read.addressExpression) {
                    is NumericLiteralValue -> {
                        val address = (read.addressExpression as NumericLiteralValue).number.toInt()
                        assignFromMemoryByte(assign.target, address, null)
                    }
                    is IdentifierReference -> {
                        assignFromMemoryByte(assign.target, null, read.addressExpression as IdentifierReference)
                    }
                    else -> {
                        throw AssemblyError("missing asm gen for memread assignment into ${assign.target}")
                    }
                }
            }
            is PrefixExpression -> {
                // TODO optimize common cases
                asmgen.translateExpression(assign.value as PrefixExpression)
                assignFromEvalResult(assign.target)
            }
            is BinaryExpression -> {
                // TODO optimize common cases
                asmgen.translateExpression(assign.value as BinaryExpression)
                assignFromEvalResult(assign.target)
            }
            is ArrayIndexedExpression -> {
                // TODO optimize common cases
                val arrayExpr = assign.value as ArrayIndexedExpression
                val arrayDt = arrayExpr.identifier.inferType(program).typeOrElse(DataType.STRUCT)
                val index = arrayExpr.arrayspec.index
                if (index is NumericLiteralValue) {
                    // constant array index value
                    val arrayVarName = asmgen.asmIdentifierName(arrayExpr.identifier)
                    val indexValue = index.number.toInt() * ArrayElementTypes.getValue(arrayDt).memorySize()
                    when (arrayDt) {
                        DataType.STR, DataType.ARRAY_UB, DataType.ARRAY_B ->
                            asmgen.out("  lda  $arrayVarName+$indexValue |  sta  $ESTACK_LO_HEX,x |  dex")
                        DataType.ARRAY_UW, DataType.ARRAY_W ->
                            asmgen.out("  lda  $arrayVarName+$indexValue |  sta  $ESTACK_LO_HEX,x |  lda  $arrayVarName+$indexValue+1 |  sta  $ESTACK_HI_HEX,x | dex")
                        DataType.ARRAY_F ->
                            asmgen.out("  lda  #<$arrayVarName+$indexValue |  ldy  #>$arrayVarName+$indexValue |  jsr  c64flt.push_float")
                        else ->
                            throw AssemblyError("weird array type")
                    }
                } else {
                    asmgen.translateArrayIndexIntoA(arrayExpr)
                    asmgen.readAndPushArrayvalueWithIndexA(arrayDt, arrayExpr.identifier)
                }
                assignFromEvalResult(assign.target)
            }
            is TypecastExpression -> {
                val cast = assign.value as TypecastExpression
                val sourceType = cast.expression.inferType(program)
                val targetType = assign.target.inferType(program, assign)
                if (sourceType.isKnown && targetType.isKnown &&
                        (sourceType.typeOrElse(DataType.STRUCT) in ByteDatatypes && targetType.typeOrElse(DataType.STRUCT) in ByteDatatypes) ||
                        (sourceType.typeOrElse(DataType.STRUCT) in WordDatatypes && targetType.typeOrElse(DataType.STRUCT) in WordDatatypes)) {
                    // no need for a type cast
                    assign.value = cast.expression
                    translate(assign)
                } else {
                    asmgen.translateExpression(assign.value as TypecastExpression)
                    assignFromEvalResult(assign.target)
                }
            }
            is FunctionCall -> {
                asmgen.translateExpression(assign.value as FunctionCall)
                assignFromEvalResult(assign.target)
            }
            is ArrayLiteralValue, is StringLiteralValue -> throw AssemblyError("no asm gen for string/array assignment  $assign")
            is StructLiteralValue -> throw AssemblyError("struct literal value assignment should have been flattened ${assign.value.position}")
            is RangeExpr -> throw AssemblyError("range expression should have been changed into array values ${assign.value.position}")
        }
    }

    internal fun assignFromEvalResult(target: AssignTarget) {
        val targetIdent = target.identifier
        when {
            target.register != null -> {
                if (target.register == Register.X)
                    throw AssemblyError("can't pop into X register - use variable instead")
                asmgen.out(" inx | ld${target.register.name.toLowerCase()}  $ESTACK_LO_HEX,x ")
            }
            targetIdent != null -> {
                val targetName = asmgen.asmIdentifierName(targetIdent)
                val targetDt = targetIdent.inferType(program).typeOrElse(DataType.STRUCT)
                when (targetDt) {
                    DataType.UBYTE, DataType.BYTE -> {
                        asmgen.out(" inx | lda  $ESTACK_LO_HEX,x  | sta  $targetName")
                    }
                    DataType.UWORD, DataType.WORD -> {
                        asmgen.out("""
                            inx
                            lda  $ESTACK_LO_HEX,x
                            sta  $targetName
                            lda  $ESTACK_HI_HEX,x
                            sta  $targetName+1
                        """)
                    }
                    DataType.FLOAT -> {
                        asmgen.out("""
                            lda  #<$targetName
                            ldy  #>$targetName
                            jsr  c64flt.pop_float
                        """)
                    }
                    else -> throw AssemblyError("weird target variable type $targetDt")
                }
            }
            target.memoryAddress != null -> {
                asmgen.out("  inx  | ldy  $ESTACK_LO_HEX,x")
                storeRegisterInMemoryAddress(Register.Y, target.memoryAddress)
            }
            target.arrayindexed != null -> {
                val arrayDt = target.arrayindexed!!.identifier.inferType(program).typeOrElse(DataType.STRUCT)
                val arrayVarName = asmgen.asmIdentifierName(target.arrayindexed!!.identifier)
                asmgen.translateExpression(target.arrayindexed!!.arrayspec.index)
                asmgen.out("  inx |  lda  $ESTACK_LO_HEX,x")
                popAndWriteArrayvalueWithIndexA(arrayDt, arrayVarName)
            }
            else -> throw AssemblyError("weird assignment target $target")
        }
    }

    internal fun assignFromAddressOf(target: AssignTarget, name: IdentifierReference) {
        val targetIdent = target.identifier
        val targetArrayIdx = target.arrayindexed
        val struct = name.memberOfStruct(program.namespace)
        val sourceName = if (struct != null) {
            // take the address of the first struct member instead
            val decl = name.targetVarDecl(program.namespace)!!
            val firstStructMember = struct.nameOfFirstMember()
            // find the flattened var that belongs to this first struct member
            val firstVarName = listOf(decl.name, firstStructMember)
            val firstVar = name.definingScope().lookup(firstVarName, name) as VarDecl
            firstVar.name
        } else {
            asmgen.fixNameSymbols(name.nameInSource.joinToString("."))
        }

        when {
            targetIdent != null -> {
                val targetName = asmgen.asmIdentifierName(targetIdent)
                asmgen.out("""
                        lda  #<$sourceName
                        ldy  #>$sourceName
                        sta  $targetName
                        sty  $targetName+1
                    """)
            }
            target.memoryAddress != null -> {
                throw AssemblyError("no asm gen for assign address $sourceName to memory word $target")
            }
            targetArrayIdx != null -> {
                val index = targetArrayIdx.arrayspec.index
                val targetName = asmgen.asmIdentifierName(targetArrayIdx.identifier)
                throw AssemblyError("no asm gen for assign address $sourceName to array $targetName [ $index ]")
            }
            else -> throw AssemblyError("no asm gen for assign address $sourceName to $target")
        }
    }

    internal fun assignFromWordVariable(target: AssignTarget, variable: IdentifierReference) {
        val sourceName = asmgen.asmIdentifierName(variable)
        val targetIdent = target.identifier
        val targetArrayIdx = target.arrayindexed
        when {
            targetIdent != null -> {
                val targetName = asmgen.asmIdentifierName(targetIdent)
                asmgen.out("""
                    lda  $sourceName
                    ldy  $sourceName+1
                    sta  $targetName
                    sty  $targetName+1
                """)
            }
            target.memoryAddress != null -> {
                throw AssemblyError("no asm gen for assign wordvar $sourceName to memory ${target.memoryAddress}")
            }
            targetArrayIdx != null -> {
                val index = targetArrayIdx.arrayspec.index
                val targetName = asmgen.asmIdentifierName(targetArrayIdx.identifier)
                asmgen.out("  lda  $sourceName |  sta  $ESTACK_LO_HEX,x |  lda  $sourceName+1 |  sta  $ESTACK_HI_HEX,x |  dex")
                asmgen.translateExpression(index)
                asmgen.out("  inx |  lda  $ESTACK_LO_HEX,x")
                val arrayDt = targetArrayIdx.identifier.inferType(program).typeOrElse(DataType.STRUCT)
                popAndWriteArrayvalueWithIndexA(arrayDt, targetName)
            }
            else -> throw AssemblyError("no asm gen for assign wordvar to $target")
        }
    }

    internal fun assignFromFloatVariable(target: AssignTarget, variable: IdentifierReference) {
        val sourceName = asmgen.asmIdentifierName(variable)
        val targetIdent = target.identifier
        val targetArrayIdx = target.arrayindexed
        when {
            targetIdent != null -> {
                val targetName = asmgen.asmIdentifierName(targetIdent)
                asmgen.out("""
                    lda  $sourceName
                    sta  $targetName
                    lda  $sourceName+1
                    sta  $targetName+1
                    lda  $sourceName+2
                    sta  $targetName+2
                    lda  $sourceName+3
                    sta  $targetName+3
                    lda  $sourceName+4
                    sta  $targetName+4
                """)
            }
            targetArrayIdx != null -> {
                val index = targetArrayIdx.arrayspec.index
                val targetName = asmgen.asmIdentifierName(targetArrayIdx.identifier)
                asmgen.out("  lda  #<$sourceName |  ldy  #>$sourceName |  jsr  c64flt.push_float")
                asmgen.translateExpression(index)
                asmgen.out("  lda  #<$targetName |  ldy  #>$targetName |  jsr  c64flt.pop_float_to_indexed_var")
            }
            else -> throw AssemblyError("no asm gen for assign floatvar to $target")
        }
    }

    internal fun assignFromByteVariable(target: AssignTarget, variable: IdentifierReference) {
        val sourceName = asmgen.asmIdentifierName(variable)
        val targetIdent = target.identifier
        val targetArrayIdx = target.arrayindexed
        when {
            target.register != null -> {
                asmgen.out("  ld${target.register.name.toLowerCase()}  $sourceName")
            }
            targetIdent != null -> {
                val targetName = asmgen.asmIdentifierName(targetIdent)
                asmgen.out("""
                    lda  $sourceName
                    sta  $targetName
                    """)
            }
            targetArrayIdx != null -> {
                val index = targetArrayIdx.arrayspec.index
                val targetName = asmgen.asmIdentifierName(targetArrayIdx.identifier)
                val arrayDt = targetArrayIdx.identifier.inferType(program).typeOrElse(DataType.STRUCT)
                asmgen.out("  lda  $sourceName |  sta  $ESTACK_LO_HEX,x |  dex")
                asmgen.translateExpression(index)
                asmgen.out("  inx |  lda  $ESTACK_LO_HEX,x")
                popAndWriteArrayvalueWithIndexA(arrayDt, targetName)
            }
            target.memoryAddress != null -> {
                val addressExpr = target.memoryAddress.addressExpression
                val addressLv = addressExpr as? NumericLiteralValue
                when {
                    addressLv != null -> asmgen.out("  lda  $sourceName |  sta  ${addressLv.number.toHex()}")
                    addressExpr is IdentifierReference -> {
                        val targetName = asmgen.asmIdentifierName(addressExpr)
                        asmgen.out("  lda  $sourceName |  sta  $targetName")
                    }
                    else -> {
                        asmgen.translateExpression(addressExpr)
                        asmgen.out("""
     inx
     lda  $ESTACK_LO_HEX,x
     ldy  $ESTACK_HI_HEX,x
     sta  (+) +1
     sty  (+) +2
     lda  $sourceName
+    sta  ${'$'}ffff      ; modified              
                            """)
                    }
                }
            }
            else -> throw AssemblyError("no asm gen for assign bytevar to $target")
        }
    }

    internal fun assignFromRegister(target: AssignTarget, register: Register) {
        val targetIdent = target.identifier
        val targetArrayIdx = target.arrayindexed
        when {
            targetIdent != null -> {
                val targetName = asmgen.asmIdentifierName(targetIdent)
                asmgen.out("  st${register.name.toLowerCase()}  $targetName")
            }
            target.register != null -> {
                when (register) {
                    Register.A -> when (target.register) {
                        Register.A -> {
                        }
                        Register.X -> asmgen.out("  tax")
                        Register.Y -> asmgen.out("  tay")
                    }
                    Register.X -> when (target.register) {
                        Register.A -> asmgen.out("  txa")
                        Register.X -> {
                        }
                        Register.Y -> asmgen.out("  txy")
                    }
                    Register.Y -> when (target.register) {
                        Register.A -> asmgen.out("  tya")
                        Register.X -> asmgen.out("  tyx")
                        Register.Y -> {
                        }
                    }
                }
            }
            target.memoryAddress != null -> {
                storeRegisterInMemoryAddress(register, target.memoryAddress)
            }
            targetArrayIdx != null -> {
                val index = targetArrayIdx.arrayspec.index
                val targetName = asmgen.asmIdentifierName(targetArrayIdx.identifier)
                when (index) {
                    is NumericLiteralValue -> {
                        val memindex = index.number.toInt()
                        when (register) {
                            Register.A -> asmgen.out("  sta  $targetName+$memindex")
                            Register.X -> asmgen.out("  stx  $targetName+$memindex")
                            Register.Y -> asmgen.out("  sty  $targetName+$memindex")
                        }
                    }
                    is RegisterExpr -> {
                        when (register) {
                            Register.A -> asmgen.out("  sta  ${C64Zeropage.SCRATCH_B1}")
                            Register.X -> asmgen.out("  stx  ${C64Zeropage.SCRATCH_B1}")
                            Register.Y -> asmgen.out("  sty  ${C64Zeropage.SCRATCH_B1}")
                        }
                        when (index.register) {
                            Register.A -> {
                            }
                            Register.X -> asmgen.out("  txa")
                            Register.Y -> asmgen.out("  tya")
                        }
                        asmgen.out("""
                            tay
                            lda  ${C64Zeropage.SCRATCH_B1}
                            sta  $targetName,y
                            """)
                    }
                    is IdentifierReference -> {
                        when (register) {
                            Register.A -> asmgen.out("  sta  ${C64Zeropage.SCRATCH_B1}")
                            Register.X -> asmgen.out("  stx  ${C64Zeropage.SCRATCH_B1}")
                            Register.Y -> asmgen.out("  sty  ${C64Zeropage.SCRATCH_B1}")
                        }
                        asmgen.out("""
                            lda  ${asmgen.asmIdentifierName(index)}
                            tay
                            lda  ${C64Zeropage.SCRATCH_B1}
                            sta  $targetName,y
                        """)
                    }
                    else -> {
                        asmgen.saveRegister(register)
                        asmgen.translateExpression(index)
                        asmgen.restoreRegister(register)
                        when (register) {
                            Register.A -> asmgen.out("  sta  ${C64Zeropage.SCRATCH_B1}")
                            Register.X -> asmgen.out("  stx  ${C64Zeropage.SCRATCH_B1}")
                            Register.Y -> asmgen.out("  sty  ${C64Zeropage.SCRATCH_B1}")
                        }
                        asmgen.out("""
                            inx
                            lda  $ESTACK_LO_HEX,x
                            tay
                            lda  ${C64Zeropage.SCRATCH_B1}
                            sta  $targetName,y  
                        """)
                    }
                }
            }
            else -> throw AssemblyError("no asm gen for assign register $register to $target")
        }
    }

    private fun storeRegisterInMemoryAddress(register: Register, memoryAddress: DirectMemoryWrite) {
        val addressExpr = memoryAddress.addressExpression
        val addressLv = addressExpr as? NumericLiteralValue
        val registerName = register.name.toLowerCase()
        when {
            addressLv != null -> asmgen.out("  st$registerName  ${addressLv.number.toHex()}")
            addressExpr is IdentifierReference -> {
                val targetName = asmgen.asmIdentifierName(addressExpr)
                when (register) {
                    Register.A -> asmgen.out("""
        ldy  $targetName
        sty  (+) +1
        ldy  $targetName+1
        sty  (+) +2
+       sta  ${'$'}ffff     ; modified""")
                    Register.X -> asmgen.out("""
        ldy  $targetName
        sty  (+) +1
        ldy  $targetName+1
        sty  (+) +2
+       stx  ${'$'}ffff    ; modified""")
                    Register.Y -> asmgen.out("""
        lda  $targetName
        sta  (+) +1
        lda  $targetName+1
        sta  (+) +2
+       sty  ${'$'}ffff    ; modified""")
                }
            }
            else -> {
                asmgen.saveRegister(register)
                asmgen.translateExpression(addressExpr)
                asmgen.restoreRegister(register)
                when (register) {
                    Register.A -> asmgen.out("  tay")
                    Register.X -> throw AssemblyError("can't use X register here")
                    Register.Y -> {
                    }
                }
                asmgen.out("""
     inx
     lda  $ESTACK_LO_HEX,x
     sta  (+) +1
     lda  $ESTACK_HI_HEX,x
     sta  (+) +2
+    sty  ${'$'}ffff      ; modified              
                            """)
            }
        }
    }

    internal fun assignFromWordConstant(target: AssignTarget, word: Int) {
        val targetIdent = target.identifier
        val targetArrayIdx = target.arrayindexed
        when {
            targetIdent != null -> {
                val targetName = asmgen.asmIdentifierName(targetIdent)
                if (word ushr 8 == word and 255) {
                    // lsb=msb
                    asmgen.out("""
                    lda  #${(word and 255).toHex()}
                    sta  $targetName
                    sta  $targetName+1
                """)
                } else {
                    asmgen.out("""
                    lda  #<${word.toHex()}
                    ldy  #>${word.toHex()}
                    sta  $targetName
                    sty  $targetName+1
                """)
                }
            }
            target.memoryAddress != null -> {
                throw AssemblyError("no asm gen for assign word $word to memory ${target.memoryAddress}")
            }
            targetArrayIdx != null -> {
                val index = targetArrayIdx.arrayspec.index
                val targetName = asmgen.asmIdentifierName(targetArrayIdx.identifier)
                // TODO optimize common cases
                asmgen.translateExpression(index)
                asmgen.out("""
                    inx
                    lda  $ESTACK_LO_HEX,x
                    asl  a
                    tay
                    lda  #<${word.toHex()}
                    sta  $targetName,y
                    lda  #>${word.toHex()}
                    sta  $targetName+1,y
                """)
            }
            else -> throw AssemblyError("no asm gen for assign word $word to $target")
        }
    }

    internal fun assignFromByteConstant(target: AssignTarget, byte: Short) {
        val targetIdent = target.identifier
        val targetArrayIdx = target.arrayindexed
        when {
            target.register != null -> {
                asmgen.out("  ld${target.register.name.toLowerCase()}  #${byte.toHex()}")
            }
            targetIdent != null -> {
                val targetName = asmgen.asmIdentifierName(targetIdent)
                asmgen.out(" lda  #${byte.toHex()} |  sta  $targetName ")
            }
            target.memoryAddress != null -> {
                asmgen.out("  ldy  #${byte.toHex()}")
                storeRegisterInMemoryAddress(Register.Y, target.memoryAddress)
            }
            targetArrayIdx != null -> {
                val index = targetArrayIdx.arrayspec.index
                val targetName = asmgen.asmIdentifierName(targetArrayIdx.identifier)
                // TODO optimize common cases
                asmgen.translateExpression(index)
                asmgen.out("""
                    inx
                    ldy  $ESTACK_LO_HEX,x
                    lda  #${byte.toHex()}
                    sta  $targetName,y
                """)
            }
            else -> throw AssemblyError("no asm gen for assign byte $byte to $target")
        }
    }

    internal fun assignFromFloatConstant(target: AssignTarget, float: Double) {
        val targetIdent = target.identifier
        val targetArrayIdx = target.arrayindexed
        if (float == 0.0) {
            // optimized case for float zero
            when {
                targetIdent != null -> {
                    val targetName = asmgen.asmIdentifierName(targetIdent)
                    asmgen.out("""
                            lda  #0
                            sta  $targetName
                            sta  $targetName+1
                            sta  $targetName+2
                            sta  $targetName+3
                            sta  $targetName+4
                        """)
                }
                targetArrayIdx != null -> {
                    val index = targetArrayIdx.arrayspec.index
                    val targetName = asmgen.asmIdentifierName(targetArrayIdx.identifier)
                    if (index is NumericLiteralValue) {
                        val indexValue = index.number.toInt() * C64MachineDefinition.FLOAT_MEM_SIZE
                        asmgen.out("""
                            lda  #0
                            sta  $targetName+$indexValue
                            sta  $targetName+$indexValue+1
                            sta  $targetName+$indexValue+2
                            sta  $targetName+$indexValue+3
                            sta  $targetName+$indexValue+4
                        """)
                    } else {
                        asmgen.translateExpression(index)
                        asmgen.out("""
                            lda  #<${targetName}
                            sta  ${C64Zeropage.SCRATCH_W1}
                            lda  #>${targetName}
                            sta  ${C64Zeropage.SCRATCH_W1 + 1}
                            jsr  c64flt.set_0_array_float
                        """)
                    }
                }
                else -> throw AssemblyError("no asm gen for assign float 0.0 to $target")
            }
        } else {
            // non-zero value
            val constFloat = asmgen.getFloatConst(float)
            when {
                targetIdent != null -> {
                    val targetName = asmgen.asmIdentifierName(targetIdent)
                    asmgen.out("""
                            lda  $constFloat
                            sta  $targetName
                            lda  $constFloat+1
                            sta  $targetName+1
                            lda  $constFloat+2
                            sta  $targetName+2
                            lda  $constFloat+3
                            sta  $targetName+3
                            lda  $constFloat+4
                            sta  $targetName+4
                        """)
                }
                targetArrayIdx != null -> {
                    val index = targetArrayIdx.arrayspec.index
                    val arrayVarName = asmgen.asmIdentifierName(targetArrayIdx.identifier)
                    if (index is NumericLiteralValue) {
                        val indexValue = index.number.toInt() * C64MachineDefinition.FLOAT_MEM_SIZE
                        asmgen.out("""
                            lda  $constFloat
                            sta  $arrayVarName+$indexValue
                            lda  $constFloat+1
                            sta  $arrayVarName+$indexValue+1
                            lda  $constFloat+2
                            sta  $arrayVarName+$indexValue+2
                            lda  $constFloat+3
                            sta  $arrayVarName+$indexValue+3
                            lda  $constFloat+4
                            sta  $arrayVarName+$indexValue+4
                        """)
                    } else {
                        // TODO the index in A below seems to be clobbered?
                        asmgen.translateArrayIndexIntoA(targetArrayIdx)
                        asmgen.out("""
                            lda  #<${constFloat}
                            sta  ${C64Zeropage.SCRATCH_W1}
                            lda  #>${constFloat}
                            sta  ${C64Zeropage.SCRATCH_W1 + 1}
                            lda  #<${arrayVarName}
                            sta  ${C64Zeropage.SCRATCH_W2}
                            lda  #>${arrayVarName}
                            sta  ${C64Zeropage.SCRATCH_W2 + 1}
                            jsr  c64flt.set_array_float
                        """)
                    }
                }
                else -> throw AssemblyError("no asm gen for assign float $float to $target")
            }
        }
    }

    internal fun assignFromMemoryByte(target: AssignTarget, address: Int?, identifier: IdentifierReference?) {
        val targetIdent = target.identifier
        val targetArrayIdx = target.arrayindexed
        if (address != null) {
            when {
                target.register != null -> {
                    asmgen.out("  ld${target.register.name.toLowerCase()}  ${address.toHex()}")
                }
                targetIdent != null -> {
                    val targetName = asmgen.asmIdentifierName(targetIdent)
                    asmgen.out("""
                        lda  ${address.toHex()}
                        sta  $targetName
                        """)
                }
                target.memoryAddress != null -> {
                    asmgen.out("  ldy  ${address.toHex()}")
                    storeRegisterInMemoryAddress(Register.Y, target.memoryAddress)
                }
                targetArrayIdx != null -> {
                    val index = targetArrayIdx.arrayspec.index
                    val targetName = asmgen.asmIdentifierName(targetArrayIdx.identifier)
                    throw AssemblyError("no asm gen for assign memory byte at $address to array $targetName [ $index ]")
                }
                else -> throw AssemblyError("no asm gen for assign memory byte $target")
            }
        } else if (identifier != null) {
            val sourceName = asmgen.asmIdentifierName(identifier)
            when {
                target.register != null -> {
                    asmgen.out("""
                        lda  $sourceName
                        sta  (+) + 1
                        lda  $sourceName+1
                        sta  (+) + 2""")
                    when (target.register) {
                        Register.A -> asmgen.out("+       lda  ${'$'}ffff\t; modified")
                        Register.X -> asmgen.out("+       ldx  ${'$'}ffff\t; modified")
                        Register.Y -> asmgen.out("+       ldy  ${'$'}ffff\t; modified")
                    }
                }
                targetIdent != null -> {
                    val targetName = asmgen.asmIdentifierName(targetIdent)
                    asmgen.out("""
    lda  $sourceName
    sta  (+) + 1
    lda  $sourceName+1
    sta  (+) + 2
+   lda  ${'$'}ffff\t; modified
    sta  $targetName""")
                }
                target.memoryAddress != null -> {
                    asmgen.out("  ldy  $sourceName")
                    storeRegisterInMemoryAddress(Register.Y, target.memoryAddress)
                }
                targetArrayIdx != null -> {
                    val index = targetArrayIdx.arrayspec.index
                    val targetName = asmgen.asmIdentifierName(targetArrayIdx.identifier)
                    throw AssemblyError("no asm gen for assign memory byte $sourceName to array $targetName [ $index ]")
                }
                else -> throw AssemblyError("no asm gen for assign memory byte $target")
            }
        }
    }

    private fun popAndWriteArrayvalueWithIndexA(arrayDt: DataType, variablename: String) {
        when (arrayDt) {
            DataType.STR, DataType.ARRAY_UB, DataType.ARRAY_B ->
                asmgen.out("  tay |  inx |  lda  $ESTACK_LO_HEX,x  | sta  $variablename,y")
            DataType.ARRAY_UW, DataType.ARRAY_W ->
                asmgen.out("  asl  a |  tay |  inx |  lda  $ESTACK_LO_HEX,x |  sta  $variablename,y |  lda  $ESTACK_HI_HEX,x |  sta $variablename+1,y")
            DataType.ARRAY_F ->
                // index * 5 is done in the subroutine that's called
                asmgen.out("""
                    sta  $ESTACK_LO_HEX,x
                    dex
                    lda  #<$variablename
                    ldy  #>$variablename
                    jsr  c64flt.pop_float_to_indexed_var
                """)
            else ->
                throw AssemblyError("weird array type")
        }
    }
}
