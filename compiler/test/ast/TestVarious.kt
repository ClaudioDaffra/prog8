package prog8tests.ast

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import prog8.ast.IFunctionCall
import prog8.ast.expressions.IdentifierReference
import prog8.ast.expressions.StringLiteral
import prog8.ast.statements.InlineAssembly
import prog8.ast.statements.VarDecl
import prog8.code.core.Position
import prog8.code.target.C64Target
import prog8.compiler.printProgram
import prog8tests.helpers.compileText

class TestVarious: FunSpec({
    test("symbol names in inline assembly blocks") {
        val names1 = InlineAssembly("""
            
        """, false, Position.DUMMY).names
        names1 shouldBe emptySet()

        val names2 = InlineAssembly("""
label:   lda #<value
         sta ${'$'}ea
         sta 123
label2: 
         sta  othervalue    ; but not these in the comments
; also not these
        ;;   ...or these
   // valid words  123456
        """, false, Position.DUMMY).names

        names2 shouldBe setOf("label", "lda", "sta", "ea", "value", "label2", "othervalue", "valid", "words")
    }

    test("array literals") {
        val text="""
%zeropage basicsafe

main {
    sub start() {
        ubyte b1
        ubyte b2
        ubyte[] array1 = [1,2,3]
        ubyte[] array2 = [9,8,7]

        uword[] @shared addresses1 = [&b1, &b2]
        uword[] @shared addresses2 = [array1, array2]
        uword[] @shared addresses3 = [&array1, &array2]
        uword[] @shared addresses4 = ["string1", "string2"]
        uword[] @shared addresses5 = [1111, 2222]
    }
}"""
        compileText(C64Target(), false, text, writeAssembly = true) shouldNotBe null
    }

    test("invalid && operator") {
        val text="""
main {
    sub start() {
        uword b1
        uword b2
        uword b3 = b1 && b2     ; invalid syntax: '&&' is not an operator, 'and' should be used instead
    }
}"""
        compileText(C64Target(), false, text, writeAssembly = false) shouldBe null
    }

    test("simple string comparison still works") {
        val src="""
        main {
            sub start() {
                ubyte @shared value
                str thing = "????"
        
                if thing=="name" {
                    value++
                }
        
                if thing!="name" {
                    value++
                }
            }
        }"""
        val result = compileText(C64Target(), optimize=false, src, writeAssembly=true)!!
        val stmts = result.program.entrypoint.statements
        stmts.size shouldBe 6
    }

    test("string concatenation and repeats") {
        val src="""
        main {
            sub start() {
                str @shared name = "part1" + "part2"
                str @shared rept = "rep"*4
                const ubyte times = 3
                name = "xx1" + "xx2"
                rept = "xyz" * (times+1)
            }
        }"""
        val result = compileText(C64Target(), optimize=false, src, writeAssembly=true)!!
        val stmts = result.program.entrypoint.statements
        stmts.size shouldBe 6
        val name1 = stmts[0] as VarDecl
        val rept1 = stmts[1] as VarDecl
        (name1.value as StringLiteral).value shouldBe "part1part2"
        (rept1.value as StringLiteral).value shouldBe "reprepreprep"
        val name2strcopy = stmts[3] as IFunctionCall
        val rept2strcopy = stmts[4] as IFunctionCall
        val name2 = name2strcopy.args.first() as IdentifierReference
        val rept2 = rept2strcopy.args.first() as IdentifierReference
        (name2.targetVarDecl(result.program)!!.value as StringLiteral).value shouldBe "xx1xx2"
        (rept2.targetVarDecl(result.program)!!.value as StringLiteral).value shouldBe "xyzxyzxyzxyz"
    }

    test("pointervariable indexing allowed with >255") {
        val src="""
main {
    sub start() {
        uword pointer = ${'$'}2000
        @(pointer+${'$'}1000) = 123
        ubyte @shared ub = @(pointer+${'$'}1000)
        pointer[${'$'}1000] = 99
        ub = pointer[${'$'}1000]
        uword index = ${'$'}1000
        pointer[index] = 55
        ub = pointer[index]
    }
}"""
        compileText(C64Target(), optimize=false, src, writeAssembly=false) shouldNotBe null
    }
})

