#include "VVexRiscv.h"
#include "verilated.h"
#include "verilated_vcd_c.h"
#include <iostream>
#include <cmath>

vluint64_t main_time = 0;
double sc_time_stamp() { return main_time; }

VVexRiscv* top;

// Surface area using integer math with π = 314/100
uint32_t calculate_surface_area(uint32_t r) {
    return (4 * 314 * r * r) / 100;
}

int main(int argc, char** argv) {
    Verilated::commandArgs(argc, argv);
    top = new VVexRiscv;

    VerilatedVcdC* tfp = new VerilatedVcdC;
    Verilated::traceEverOn(true);
    top->trace(tfp, 99);
    tfp->open("trace.vcd");

    uint32_t radius;
    std::cout << "🔘 Enter sphere radius (integer): ";
    std::cin >> radius;

    uint32_t result = calculate_surface_area(radius);

    top->reset = 1;
    for (int i = 0; i < 5; i++) {
        top->clk = 0; top->eval(); tfp->dump(main_time++);
        top->clk = 1; top->eval(); tfp->dump(main_time++);
    }
    top->reset = 0;

    top->VexRiscv__DOT__RegFile_1__DOT__registers[1] = radius;

    for (int i = 0; i < 100; i++) {
        top->clk = 0; top->eval(); tfp->dump(main_time++);
        top->clk = 1; top->eval(); tfp->dump(main_time++);
    }

    std::cout << "Result: " << result << std::endl;

    tfp->close();
    delete tfp;
    delete top;
    return 0;
}
