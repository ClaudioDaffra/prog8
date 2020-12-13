%import textio
%import diskio
%import floats
%import graphics
%zeropage basicsafe
%import test_stack
%option no_sysinit

main {
    sub start () {

        uword bitmap_load_address = progend()
        uword max_bitmap_size = $9eff - bitmap_load_address         ; TODO why is this not optimized away?

        ; foo(2**x)     ; TODO arg is zero if x=8, in the function. Param type uword. fix that . also check bit shift
        ; foo($0001<<x)     ; TODO fix crash

        uword xx = progend()
        txt.print_uwhex(xx, 1)
        txt.print_uwhex(progend(), 1)

        uword scanline_data_ptr= $6000
        uword pixptr = x/8 + scanline_data_ptr      ; TODO why is this code so much larger than the following:
        uword pixptr2 = scanline_data_ptr + x/8


        test_stack.test()
    }

}
