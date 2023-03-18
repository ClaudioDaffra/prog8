package prog8.optimizer

import prog8.ast.IStatementContainer
import prog8.ast.Node
import prog8.ast.Program
import prog8.ast.expressions.BinaryExpression
import prog8.ast.statements.AssignTarget
import prog8.ast.statements.Assignment
import prog8.ast.statements.AssignmentOrigin
import prog8.ast.walk.AstWalker
import prog8.ast.walk.IAstModification
import prog8.code.core.AugmentAssignmentOperators
import prog8.code.core.CompilationOptions
import prog8.code.core.DataType
import prog8.code.target.VMTarget


class BinExprSplitter(private val program: Program, private val options: CompilationOptions) : AstWalker() {

    override fun after(assignment: Assignment, parent: Node): Iterable<IAstModification> {

        if(options.compTarget.name == VMTarget.NAME)
            return noModifications  // don't split expressions when targeting the vm codegen, it handles nested expressions well
        if(options.useRPN)      // TODO RPN does this make a difference?
            return noModifications

        if(assignment.value.inferType(program) istype DataType.FLOAT && !options.optimizeFloatExpressions)
            return noModifications

        val binExpr = assignment.value as? BinaryExpression
        if (binExpr != null) {

            if(binExpr.operator in AugmentAssignmentOperators && isSimpleTarget(assignment.target)) {
                if(assignment.target isSameAs binExpr.right)
                    return noModifications
                if(assignment.target isSameAs binExpr.left) {
                    if(binExpr.right.isSimple)
                        return noModifications
                    val leftBx = binExpr.left as? BinaryExpression
                    if(leftBx!=null && (!leftBx.left.isSimple || !leftBx.right.isSimple))
                        return noModifications
                    val rightBx = binExpr.right as? BinaryExpression
                    if(rightBx!=null && (!rightBx.left.isSimple || !rightBx.right.isSimple))
                        return noModifications
                }

                if(binExpr.right.isSimple) {
                    val firstAssign = Assignment(assignment.target.copy(), binExpr.left, AssignmentOrigin.OPTIMIZER, binExpr.left.position)
                    val targetExpr = assignment.target.toExpression()
                    val augExpr = BinaryExpression(targetExpr, binExpr.operator, binExpr.right, binExpr.right.position)
                    return listOf(
                        IAstModification.ReplaceNode(binExpr, augExpr, assignment),
                        IAstModification.InsertBefore(assignment, firstAssign, assignment.parent as IStatementContainer)
                    )
                }
            }

            // Further unraveling of binary expressions is really complicated here and
            // often results in much bigger code, thereby defeating the purpose a bit.
            // All in all this should probably be fixed in a better code generation backend
            // that doesn't require this at all.
        }

        return noModifications
    }

    private fun isSimpleTarget(target: AssignTarget) =
            if (target.identifier!=null || target.memoryAddress!=null)
                !target.isIOAddress(options.compTarget.machine)
            else
                false

}
