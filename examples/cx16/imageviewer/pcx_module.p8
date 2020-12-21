%target cx16
%import graphics
%import textio
%import diskio

pcx_module {

    sub show_image(uword filenameptr) -> ubyte {
        ubyte load_ok = false

        if diskio.f_open(8, filenameptr) {
            ubyte[128] header
            uword size = diskio.f_read(header, 128)
            if size==128 {
                if header[0] == $0a and header[2] == 1 {
                    ubyte bits_per_pixel = header[3]
                    if bits_per_pixel==1 or bits_per_pixel==4 or bits_per_pixel==8 {
                        uword width = mkword(header[$09], header[$08]) - mkword(header[$05], header[$04]) + 1
                        uword height = mkword(header[$0b], header[$0a]) - mkword(header[$07], header[$06]) + 1
                        ubyte number_of_planes = header[$41]
                        uword palette_format = mkword(header[$45], header[$44])
                        uword num_colors = 2**bits_per_pixel
                        if number_of_planes == 1 {
                            if (width & 7) == 0 {
                                graphics.clear_screen(1, 0)
                                if palette_format==2
                                    palette.set_grayscale()
                                else if num_colors == 16
                                    palette.set_rgb8(&header + $10, 16)
                                else if num_colors == 2
                                    palette.set_monochrome()
                                when bits_per_pixel {
                                    8 -> load_ok = bitmap.do8bpp(width, height)
                                    4 -> load_ok = bitmap.do4bpp(width, height)
                                    1 -> load_ok = bitmap.do1bpp(width, height)
                                }
                                if load_ok {
                                    load_ok = c64.CHRIN()
                                    if load_ok == 12 {
                                        ; there is 256 colors of palette data at the end
                                        uword palette_mem = progend()
                                        load_ok = false
                                        size = diskio.f_read(palette_mem, 3*256)
                                        if size==3*256 {
                                            load_ok = true
                                            palette.set_rgb8(palette_mem, num_colors)
                                        }
                                    }
                                }
                            } else
                                txt.print("width not multiple of 8!\n")
                        } else
                            txt.print("has >256 colors!\n")
                    }
                }
            }
            diskio.f_close()
        }

        return load_ok
    }
}

bitmap {

    uword offsetx
    uword offsety
    uword py
    uword px
    ubyte y_ok
    ubyte status

    sub start_plot(uword width, uword height) {
        offsetx = 0
        offsety = 0
        y_ok = true
        py = 0
        px = 0
        if width < graphics.WIDTH
            offsetx = (graphics.WIDTH - width - 1) / 2
        if height < graphics.HEIGHT
            offsety = (graphics.HEIGHT - height - 1) / 2
        status = (not c64.READST()) or c64.READST()==64
    }

    sub set_cursor(uword x, uword y) {
        cx16.r0=offsetx+x
        cx16.r1=offsety+y
        cx16.FB_cursor_position()
    }

    sub next_scanline() {
        px = 0
        py++
        y_ok = py < graphics.HEIGHT-1
        set_cursor(0, py)
        status = (not c64.READST()) or c64.READST()==64
    }

    sub do1bpp(uword width, uword height) -> ubyte {
        start_plot(width, height)
        set_cursor(0, 0)
        while py < height and status {
            ubyte b = c64.CHRIN()
            if b>>6==3 {
                b &= %00111111
                ubyte dat = c64.CHRIN()
                repeat b {
                    if y_ok
                        cx16.FB_set_8_pixels_opaque(255, 255, 0, dat)
                    px += 8
                }
            } else {
                if y_ok
                    cx16.FB_set_8_pixels_opaque(255, 255, 0, b)
                px += 8
            }
            if px==width
                next_scanline()
        }

        return status
    }

    sub do4bpp(uword width, uword height) -> ubyte {
        start_plot(width, height)
        set_cursor(0, 0)
        while py < height and status {
            ubyte b = c64.CHRIN()
            if b>>6==3 {
                b &= %00111111
                ubyte dat = c64.CHRIN()
                if y_ok
                    repeat b {
                        cx16.FB_set_pixel(dat>>4)
                        cx16.FB_set_pixel(dat & 15)
                    }
                px += b*2
            } else {
                if y_ok {
                    cx16.FB_set_pixel(b>>4)
                    cx16.FB_set_pixel(b & 15)
                }
                px += 2
            }
            if px==width
                next_scanline()
        }

        return status
    }

    sub do8bpp(uword width, uword height) -> ubyte {
        start_plot(width, height)
        set_cursor(0, 0)
        while py < height and status {
            ubyte b = c64.CHRIN()
            if b>>6==3 {
                b &= %00111111
                ubyte dat = c64.CHRIN()
                if y_ok
                    repeat b
                        cx16.FB_set_pixel(dat)
                px += b
            } else {
                if y_ok
                    cx16.FB_set_pixel(b)
                px++
            }
            if px==width
                next_scanline()
        }

        return status
    }
}
