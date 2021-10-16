package prog8tests.helpers

import prog8.ast.base.Position
import prog8.compiler.IErrorReporter

class ErrorReporterForTests: IErrorReporter {


    val errors = mutableListOf<String>()
    val warnings = mutableListOf<String>()

    override fun err(msg: String, position: Position) {
        errors.add(msg)
    }

    override fun warn(msg: String, position: Position) {
        warnings.add(msg)
    }

    override fun noErrors(): Boolean  = errors.isEmpty()

    override fun report() {
        errors.clear()
        warnings.clear()
    }
}