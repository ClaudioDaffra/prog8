%import c64utils
%option enable_floats

~ main {

    const uword width = 320
    const uword height = 200

    sub start()  {

        vm_gfx_clearscr(0)

        float t
        ubyte color

        while true {
            float x = sin(t*1.01) + cos(t*1.1234)
            float y = cos(t) + sin(t*0.03456)
            vm_gfx_pixel(screenx(x), screeny(y), color//16)
            t  += 0.01
            color++
        }
    }

    sub screenx(float x) -> word {
        ; return (x/4.1* (width as float) as word) + width // 2       ; @todo fix calculation
        float wf = width
        return (x/4.1* wf + wf / 2)   as word
    }
    sub screeny(float y) -> word {
        ;return (y/4.1 * (height as float) as word) + height // 2        ; @todo fix calculation
        float hf = height
        return (y/4.1 * hf + hf/ 2)     as word
    }
}
