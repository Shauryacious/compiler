#include "VVexRiscv.h"
#include "verilated.h"
#include "verilated_vcd_c.h"
#include <iostream>

vluint64_t main_time = 0;
double sc_time_stamp() { return main_time; }

int main(int argc, char** argv) {
    Verilated::commandArgs(argc, argv);
    VVexRiscv* top = new VVexRiscv;

    Verilated::traceEverOn(true);
    VerilatedVcdC* tfp = new VerilatedVcdC;
    top->trace(tfp, 99);
    tfp->open("trace.vcd");

    // --- Reset Sequence ---
    top->reset = 1;
    for (int i = 0; i < 10; i++) {
        top->clk = 0; top->eval(); tfp->dump(main_time++);
        top->clk = 1; top->eval(); tfp->dump(main_time++);
    }
    top->reset = 0;

    // --- Provide input radius ---
    uint32_t radius = 5; // You can change this
    bool input_sent = false;
    bool output_received = false;
    uint32_t result = 0;

    for (int cycle = 0; cycle < 200; cycle++) {
        // Clock low
        top->clk = 0;

        // Send input once
        if (!input_sent && top->io_input_ready) {
            top->io_input_valid = 1;
            top->io_input_payload = radius;
        } else {
            top->io_input_valid = 0;
        }

        top->eval();
        tfp->dump(main_time++);

        // Clock high
        top->clk = 1;
        top->eval();
        tfp->dump(main_time++);

        // Check for output
        if (top->io_output_valid) {
            result = top->io_output_payload;
            output_received = true;
            break;
        }
    }

    if (output_received) {
        std::cout << "Result"<< radius << std::endl;
    } else {
        std::cout << "❌ No result received from CPU within simulation cycles.\n";
    }

    tfp->close();
    delete tfp;
    delete top;
    return 0;
}
