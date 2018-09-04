package il65

import java.nio.file.Paths
import il65.ast.*
import il65.parser.*
import il65.compiler.*
import il65.optimizing.optimizeExpressions
import il65.optimizing.optimizeStatements
import kotlin.system.exitProcess


fun main(args: Array<String>) {
    try {
        println("\nIL65 compiler by Irmen de Jong (irmen@razorvine.net)")
        println("This software is licensed under the GNU GPL 3.0, see https://www.gnu.org/licenses/gpl.html\n")

        // import main module and process additional imports

        if(args.size != 1) {
            System.err.println("module filename argument missing")
            exitProcess(1)
        }

        val startTime = System.currentTimeMillis()
        val filepath = Paths.get(args[0]).normalize()
        val moduleAst = importModule(filepath)
        moduleAst.linkParents()
        val globalNameSpaceBeforeOptimization = moduleAst.definingScope()


        // perform syntax checks and optimizations
        moduleAst.checkIdentifiers()
        moduleAst.optimizeExpressions(globalNameSpaceBeforeOptimization)
        moduleAst.checkValid(globalNameSpaceBeforeOptimization)          // check if tree is valid
        val allScopedSymbolDefinitions = moduleAst.checkIdentifiers()
        moduleAst.optimizeStatements(globalNameSpaceBeforeOptimization, allScopedSymbolDefinitions)
        val globalNamespaceAfterOptimize = moduleAst.definingScope()    // it could have changed in the meantime
        moduleAst.checkValid(globalNamespaceAfterOptimize)          // check if final tree is valid
        moduleAst.checkRecursion()      // check if there are recursive subroutine calls

        // globalNamespaceAfterOptimize.debugPrint()

        // determine special compiler options

        val options = moduleAst.statements.filter { it is Directive && it.directive=="%option" }.flatMap { (it as Directive).args }.toSet()
        val outputType = (moduleAst.statements.singleOrNull { it is Directive && it.directive=="%output"}
                as? Directive)?.args?.single()?.name?.toUpperCase()
        val launcherType = (moduleAst.statements.singleOrNull { it is Directive && it.directive=="%launcher"}
                as? Directive)?.args?.single()?.name?.toUpperCase()
        val zpType = (moduleAst.statements.singleOrNull { it is Directive && it.directive=="%zeropage"}
                as? Directive)?.args?.single()?.name?.toUpperCase()

        val compilerOptions = CompilationOptions(
                if(outputType==null) OutputType.PRG else OutputType.valueOf(outputType),
                if(launcherType==null) LauncherType.BASIC else LauncherType.valueOf(launcherType),
                if(zpType==null) ZeropageType.COMPATIBLE else ZeropageType.valueOf(zpType),
                options.contains(DirectiveArg(null, "enable_floats", null))
        )


        // compile the syntax tree into intermediate form, and optimize that

        val compiler = Compiler(compilerOptions, globalNamespaceAfterOptimize)
        val intermediate = compiler.compile(moduleAst)
        intermediate.optimize()

//        val assembler = intermediate.compileToAssembly()
//        assembler.assemble(compilerOptions, "input", "output")
//        val monitorfile = assembler.generateBreakpointList()

        val endTime = System.currentTimeMillis()
        println("Compilation time: ${(endTime-startTime)/1000.0} sec.")

//        // start the vice emulator
//        val program = "foo"
//        val cmdline = listOf("x64", "-moncommands", monitorfile,
//                "-autostartprgmode", "1", "-autostart-warp", "-autostart", program)
//        ProcessBuilder(cmdline).inheritIO().start()

    } catch (px: ParsingFailedError) {
        System.err.println(px.message)
        exitProcess(1)
    }
}
