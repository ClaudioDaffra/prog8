; Prog8 definitions for the Text I/O console routines for the Virtual Machine
;
; Written by Irmen de Jong (irmen@razorvine.net) - license: GNU GPL 3.0

%import syslib


txt {

sub  clear_screen() {
    syscall1(3, "\x1b[2J\x1B[H")
}

sub nl() {
    txt.chrout('\n')
}

sub spc() {
    txt.chrout(' ')
}

sub chrout(ubyte char) {
    syscall1(2, char)
}

sub  print (str text) {
    syscall1(3, text)
}

sub  print_ub0  (ubyte value) {
    ; ---- print the ubyte in A in decimal form, with left padding 0s (3 positions total)
    ; TODO use conv module?
    ubyte hundreds = value / 100
    value -= hundreds*100
    ubyte tens = value / 10
    value -= tens*10
    chrout(hundreds+'0')
    chrout(tens+'0')
    chrout(value+'0')
}

sub  print_ub  (ubyte value)  {
    ; ---- print the ubyte in decimal form, without left padding 0s
    ; TODO use conv module?
    ubyte hundreds = value / 100
    value -= hundreds*100
    ubyte tens = value / 10
    value -= tens*10
    if hundreds
        goto print_hundreds
    if tens
        goto print_tens
    goto print_ones
print_hundreds:
    chrout(hundreds+'0')
print_tens:
    chrout(tens+'0')
print_ones:
    chrout(value+'0')
}

sub  print_b  (byte value)   {
    ; ---- print the byte in decimal form, without left padding 0s
    ; TODO use conv module?
}

sub  print_ubhex  (ubyte value, ubyte prefix)  {
    ; ---- print the ubyte in hex form
    ; TODO use conv module?
}

sub  print_ubbin  (ubyte value, ubyte prefix) {
    ; ---- print the ubyte in binary form
    ; TODO use conv module?
}

sub  print_uwbin  (uword value, ubyte prefix)  {
    ; ---- print the uword in binary form
    ; TODO use conv module?
}

sub  print_uwhex  (uword value, ubyte prefix) {
    ; ---- print the uword in hexadecimal form (4 digits)
    ; TODO use conv module?
}

sub  print_uw0  (uword value)  {
    ; ---- print the uword value in decimal form, with left padding 0s (5 positions total)
    ; TODO use conv module?
    ubyte tenthousands = value / 10000 as ubyte
    value -= tenthousands * 10000
    ubyte thousands = value / 1000 as ubyte
    value -= thousands * 1000
    ubyte hundreds = value / 100 as ubyte
    value -= hundreds*100
    ubyte tens = value / 10 as ubyte
    value -= tens*10
    chrout(tenthousands+'0')
    chrout(thousands+'0')
    chrout(hundreds+'0')
    chrout(tens+'0')
    chrout(value as ubyte + '0')
}

sub  print_uw  (uword value)  {
    ; ---- print the uword in decimal form, without left padding 0s
    ubyte tenthousands = value / 10000 as ubyte
    value -= tenthousands * 10000
    ubyte thousands = value / 1000 as ubyte
    value -= thousands * 1000
    ubyte hundreds = value / 100 as ubyte
    value -= hundreds*100
    ubyte tens = value / 10 as ubyte
    value -= tens*10
    if tenthousands
        goto print_tenthousands
    if thousands
        goto print_thousands
    if hundreds
        goto print_hundreds
    if tens
        goto print_tens
    goto print_ones
print_tenthousands:
    chrout(tenthousands+'0')
print_thousands:
    chrout(thousands+'0')
print_hundreds:
    chrout(hundreds+'0')
print_tens:
    chrout(tens+'0')
print_ones:
    chrout(value as ubyte + '0')
}

sub  print_w  (word value) {
    ; ---- print the (signed) word in decimal form, without left padding 0's
    ; TODO use conv module?
}

sub  input_chars  (uword buffer) -> ubyte  {
    ; ---- Input a string (max. 80 chars) from the keyboard. Returns length of input. (string is terminated with a 0 byte as well)
    ;      It assumes the keyboard is selected as I/O channel!
    ;  TODO
    return 0
}

}