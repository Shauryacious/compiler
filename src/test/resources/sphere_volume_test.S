.section .text
.globl main

main:
    li x1, 2           # a = 2
    li x2, 3           # b = 3

    .word 0x007101B3   # Encoded custom instruction manually:
                       # funct7 = 0000001, rs2 = x2, rs1 = x1, funct3 = 000, rd = x3, opcode = 0110011

    # Now x3 should contain: 2 + 3*5 + 10*2 = 2 + 15 + 20 = 37

    # Store x3 in memory to inspect
    la x5, result
    sw x3, 0(x5)

    j end

.data
result: .word 0

end:
    j end