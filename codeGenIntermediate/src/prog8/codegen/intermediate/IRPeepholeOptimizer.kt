package prog8.codegen.intermediate

import prog8.code.core.IErrorReporter
import prog8.intermediate.*

class IRPeepholeOptimizer(private val irprog: IRProgram) {
    fun optimize(optimizationsEnabled: Boolean, errors: IErrorReporter) {
        if(!optimizationsEnabled)
            return optimizeOnlyJoinChunks()

        peepholeOptimize()
        val remover = IRUnusedCodeRemover(irprog, errors)
        var totalRemovals = 0
        do {
            val numRemoved = remover.optimize()
            totalRemovals += numRemoved
        } while(numRemoved>0 && errors.noErrors())
        errors.report()

        if(totalRemovals>0) {
            irprog.linkChunks()  // re-link again.
        }
    }

    private fun optimizeOnlyJoinChunks() {
        irprog.foreachSub { sub ->
            joinChunks(sub)
            removeEmptyChunks(sub)
            joinChunks(sub)
        }
        irprog.linkChunks() // re-link
    }

    private fun peepholeOptimize() {
        irprog.foreachSub { sub ->
            joinChunks(sub)
            removeEmptyChunks(sub)
            joinChunks(sub)

            sub.chunks.withIndex().forEach { (index, chunk1) ->
                // we don't optimize Inline Asm chunks here.
                val chunk2 = if(index<sub.chunks.size-1) sub.chunks[index+1] else null
                if(chunk1 is IRCodeChunk) {
                    do {
                        val indexedInstructions = chunk1.instructions.withIndex()
                            .map { IndexedValue(it.index, it.value) }
                        val changed = removeNops(chunk1, indexedInstructions)
                                || removeDoubleLoadsAndStores(chunk1, indexedInstructions)       // TODO not yet implemented
                                || removeUselessArithmetic(chunk1, indexedInstructions)
                                || removeNeedlessCompares(chunk1, indexedInstructions)
                                || removeWeirdBranches(chunk1, chunk2, indexedInstructions)
                                || removeDoubleSecClc(chunk1, indexedInstructions)
                                || cleanupPushPop(chunk1, indexedInstructions)
                        // TODO other optimizations
                    } while (changed)
                }
            }
            removeEmptyChunks(sub)
        }

        // TODO also do register optimization step here at the end?

        irprog.linkChunks()  // re-link
    }

    private fun removeEmptyChunks(sub: IRSubroutine) {
        if(sub.chunks.isEmpty())
            return

        /*
        Empty Code chunk with label ->
            If next chunk has no label -> move label to next chunk, remove original
            If next chunk has label -> label name should be the same, remove original. Otherwise FOR NOW leave it in place. (TODO: merge both labels into 1)
            If is last chunk -> keep chunk in place because of the label.
        Empty Code chunk without label ->
            should not have been generated! ERROR.
         */


        val relabelChunks = mutableListOf<Pair<Int, String>>()
        val removeChunks = mutableListOf<Int>()

        sub.chunks.withIndex().forEach { (index, chunk) ->
            if(chunk is IRCodeChunk && chunk.instructions.isEmpty()) {
                if(chunk.label==null) {
                    removeChunks += index
                } else {
                    if (index < sub.chunks.size - 1) {
                        val nextchunk = sub.chunks[index + 1]
                        if (nextchunk.label == null) {
                            // can transplant label to next chunk and remove this empty one.
                            relabelChunks += Pair(index + 1, chunk.label!!)
                            removeChunks += index
                        } else {
                            if (chunk.label == nextchunk.label)
                                removeChunks += index
                            else {
                                // TODO: merge labels on same chunk
                            }
                        }
                    }
                }
            }
        }

        relabelChunks.forEach { (index, label) ->
            val chunk = IRCodeChunk(label, null)
            chunk.instructions += sub.chunks[index].instructions
            sub.chunks[index] = chunk
        }
        removeChunks.reversed().forEach { index -> sub.chunks.removeAt(index) }
    }

    private fun joinChunks(sub: IRSubroutine) {
        // Subroutine contains a list of chunks. Some can be joined into one.

        if(sub.chunks.isEmpty())
            return

        fun mayJoinCodeChunks(previous: IRCodeChunkBase, chunk: IRCodeChunkBase): Boolean {
            if(chunk.label!=null)
                return false
            if(previous is IRCodeChunk && chunk is IRCodeChunk) {
                // if the previous chunk doesn't end in a jump or a return, flow continues into the next chunk
                val lastInstruction = previous.instructions.lastOrNull()
                if(lastInstruction!=null)
                    return lastInstruction.opcode !in OpcodesThatJump
                return true
            }
            return false
        }

        val chunks = mutableListOf<IRCodeChunkBase>()
        chunks += sub.chunks[0]
        for(ix in 1 until sub.chunks.size) {
            val lastChunk = chunks.last()
            val candidate = sub.chunks[ix]
            when(candidate) {
                is IRCodeChunk -> {
                    if(mayJoinCodeChunks(lastChunk, candidate)) {
                        lastChunk.instructions += candidate.instructions
                        lastChunk.next = candidate.next
                    }
                    else
                        chunks += candidate
                }
                is IRInlineAsmChunk -> {
                    if(candidate.label!=null)
                        chunks += candidate
                    else if(lastChunk.isEmpty()) {
                        val label = lastChunk.label
                        if(label!=null)
                            chunks += IRInlineAsmChunk(label, candidate.assembly, candidate.isIR, candidate.next)
                        else
                            chunks += candidate
                    }
                }
                is IRInlineBinaryChunk -> {
                    if(candidate.label!=null)
                        chunks += candidate
                    else if(lastChunk.isEmpty()) {
                        val label = lastChunk.label
                        if(label!=null)
                            chunks += IRInlineBinaryChunk(label, candidate.data, candidate.next)
                        else
                            chunks += candidate
                    }
                }
            }
        }
        sub.chunks.clear()
        sub.chunks += chunks
    }

    private fun cleanupPushPop(chunk: IRCodeChunk, indexedInstructions: List<IndexedValue<IRInstruction>>): Boolean {
        //  push followed by pop to same target, or different target->replace with load
        var changed = false
        indexedInstructions.reversed().forEach { (idx, ins) ->
            if(ins.opcode== Opcode.PUSH) {
                if(idx < chunk.instructions.size-1) {
                    val insAfter = chunk.instructions[idx+1]
                    if(insAfter.opcode == Opcode.POP) {
                        if(ins.reg1==insAfter.reg1) {
                            chunk.instructions.removeAt(idx)
                            chunk.instructions.removeAt(idx)
                        } else {
                            chunk.instructions[idx] = IRInstruction(Opcode.LOADR, ins.type, reg1=insAfter.reg1, reg2=ins.reg1)
                            chunk.instructions.removeAt(idx+1)
                        }
                        changed = true
                    }
                }
            }
        }
        return changed
    }

    private fun removeDoubleSecClc(chunk: IRCodeChunk, indexedInstructions: List<IndexedValue<IRInstruction>>): Boolean {
        //  double sec, clc
        //  sec+clc or clc+sec
        var changed = false
        indexedInstructions.reversed().forEach { (idx, ins) ->
            if(ins.opcode== Opcode.SEC || ins.opcode== Opcode.CLC) {
                if(idx < chunk.instructions.size-1) {
                    val insAfter = chunk.instructions[idx+1]
                    if(insAfter.opcode == ins.opcode) {
                        chunk.instructions.removeAt(idx)
                        changed = true
                    }
                    else if(ins.opcode== Opcode.SEC && insAfter.opcode== Opcode.CLC) {
                        chunk.instructions.removeAt(idx)
                        changed = true
                    }
                    else if(ins.opcode== Opcode.CLC && insAfter.opcode== Opcode.SEC) {
                        chunk.instructions.removeAt(idx)
                        changed = true
                    }
                }
            }
        }
        return changed
    }

    private fun removeWeirdBranches(chunk: IRCodeChunk, nextChunk: IRCodeChunkBase?, indexedInstructions: List<IndexedValue<IRInstruction>>): Boolean {
        var changed = false
        indexedInstructions.reversed().forEach { (idx, ins) ->
            val labelSymbol = ins.labelSymbol

            // remove jump/branch to label immediately below (= next chunk if it has that label)
            if(ins.opcode== Opcode.JUMP && labelSymbol!=null) {
                if(idx==chunk.instructions.size-1 && ins.branchTarget===nextChunk) {
                    chunk.instructions.removeAt(idx)
                    changed = true
                }
            }

            // remove useless RETURN
            if(idx>0 && (ins.opcode == Opcode.RETURN || ins.opcode==Opcode.RETURNR)) {
                val previous = chunk.instructions[idx-1]
                if(previous.opcode in OpcodesThatJump) {
                    chunk.instructions.removeAt(idx)
                    changed = true
                }
            }

            // replace subsequent opcodes that jump by just the first
            if(idx>0 && (ins.opcode in OpcodesThatJump)) {
                val previous = chunk.instructions[idx-1]
                if(previous.opcode in OpcodesThatJump) {
                    chunk.instructions.removeAt(idx)
                    changed = true
                }
            }

            // replace call + return --> jump
            // This can no longer be done here on the IR level, with the current CALL opcode that encodes the full subroutine call setup.
            // If machine code is ever generated from this IR, *that* should possibly optimize the JSR + RTS into a JMP.
//            if(idx>0 && ins.opcode==Opcode.RETURN) {
//                val previous = chunk.instructions[idx-1]
//                if(previous.opcode==Opcode.CALL) {
//                    chunk.instructions[idx-1] = IRInstruction(Opcode.JUMP, address = previous.address, labelSymbol = previous.labelSymbol, branchTarget = previous.branchTarget)
//                    chunk.instructions.removeAt(idx)
//                    changed = true
//                }
//            }
        }
        return changed
    }

    private fun removeNeedlessCompares(chunk: IRCodeChunk, indexedInstructions: List<IndexedValue<IRInstruction>>): Boolean {
        // a CMPI with 0, after an instruction like LOAD that already sets the status bits, can be removed.
        // but only if the instruction after it is not using the Carry bit because that won't be set by a LOAD instruction etc.
        var changed = false
        indexedInstructions.reversed().forEach { (idx, ins) ->
            if(idx>0 && idx<(indexedInstructions.size-1) && ins.opcode==Opcode.CMPI && ins.immediate==0) {
                val previous = indexedInstructions[idx-1].value
                if(previous.opcode in OpcodesThatSetStatusbitsIncludingCarry) {
                    chunk.instructions.removeAt(idx)
                    changed = true
                } else if(previous.opcode in OpcodesThatSetStatusbitsButNotCarry) {
                    val next = indexedInstructions[idx+1].value
                    if(next.opcode !in arrayOf(Opcode.BSTCC, Opcode.BSTCS, Opcode.BSTPOS, Opcode.BSTNEG)) {
                        chunk.instructions.removeAt(idx)
                        changed = true
                    }
                }
            }
        }
        return changed
    }

    private fun removeUselessArithmetic(chunk: IRCodeChunk, indexedInstructions: List<IndexedValue<IRInstruction>>): Boolean {
        // note: this is hard to solve for the non-immediate instructions atm because the values are loaded into registers first
        var changed = false
        indexedInstructions.reversed().forEach { (idx, ins) ->
            when (ins.opcode) {
                Opcode.DIV, Opcode.DIVS, Opcode.MUL, Opcode.MOD -> {
                    if (ins.immediate == 1) {
                        chunk.instructions.removeAt(idx)
                        changed = true
                    }
                }
                Opcode.ADD, Opcode.SUB -> {
                    if (ins.immediate == 1) {
                        chunk.instructions[idx] = IRInstruction(
                            if (ins.opcode == Opcode.ADD) Opcode.INC else Opcode.DEC,
                            ins.type,
                            ins.reg1
                        )
                        changed = true
                    } else if (ins.immediate == 0) {
                        chunk.instructions.removeAt(idx)
                        changed = true
                    }
                }
                Opcode.AND -> {
                    if (ins.immediate == 0) {
                        chunk.instructions[idx] = IRInstruction(Opcode.LOAD, ins.type, reg1 = ins.reg1, immediate = 0)
                        changed = true
                    } else if (ins.immediate == 255 && ins.type == IRDataType.BYTE) {
                        chunk.instructions.removeAt(idx)
                        changed = true
                    } else if (ins.immediate == 65535 && ins.type == IRDataType.WORD) {
                        chunk.instructions.removeAt(idx)
                        changed = true
                    }
                }
                Opcode.OR -> {
                    if (ins.immediate == 0) {
                        chunk.instructions.removeAt(idx)
                        changed = true
                    } else if ((ins.immediate == 255 && ins.type == IRDataType.BYTE) || (ins.immediate == 65535 && ins.type == IRDataType.WORD)) {
                        chunk.instructions[idx] = IRInstruction(Opcode.LOAD, ins.type, reg1 = ins.reg1, immediate = ins.immediate)
                        changed = true
                    }
                }
                Opcode.XOR -> {
                    if (ins.immediate == 0) {
                        chunk.instructions.removeAt(idx)
                        changed = true
                    }
                }
                else -> {}
            }
        }
        return changed
    }

    private fun removeNops(chunk: IRCodeChunk, indexedInstructions: List<IndexedValue<IRInstruction>>): Boolean {
        var changed = false
        indexedInstructions.reversed().forEach { (idx, ins) ->
            if (ins.opcode == Opcode.NOP) {
                changed = true
                chunk.instructions.removeAt(idx)
            }
        }
        return changed
    }

    private fun removeDoubleLoadsAndStores(chunk: IRCodeChunk, indexedInstructions: List<IndexedValue<IRInstruction>>): Boolean {
        var changed = false
        indexedInstructions.forEach { (idx, ins) ->

            // TODO: detect multiple loads to the same target registers, only keep first (if source is not I/O memory)
            // TODO: detect multiple stores to the same target, only keep first (if target is not I/O memory)
            // TODO: detect multiple float ffrom/fto to the same target, only keep first
            // TODO: detect multiple sequential rnd with same reg1, only keep one
            // TODO: detect subsequent same xors/nots/negs, remove the pairs completely as they cancel out
            // TODO: detect multiple same ands, ors; only keep first
            // TODO: (hard) detect multiple registers being assigned the same value (and not changed) - use only 1 of them
            // ...
        }
        return changed
    }
}