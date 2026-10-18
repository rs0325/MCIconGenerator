/* Linux amd64の実行経路でCPUID.SSE4_1を検査する。libcを使用しない。 */
.text
.global _start
_start:
    mov $1, %eax
    cpuid
    test $0x80000, %ecx
    jz missing
    xor %edi, %edi
    jmp finish
missing:
    mov $2, %edi
finish:
    mov $60, %eax
    syscall
