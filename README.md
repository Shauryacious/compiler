---
# **Compiler Design Assignment**
---

## **Title:**

**Designing a Custom Instruction for Surface Area of a Sphere**

---

## **Equation Used:**

\[
\text{Surface Area} = 4 \times \pi \times r^2
\]  
(Approximated as: `4 * 3.14 * r * r`)

---

## **Instruction Name:**

**SurfaceAreaPlugin**

---

## **Step-by-Step Implementation Guide**

### **Step 1: Clone the VexRiscv Repository**

Open your terminal and run:

```bash
git clone https://github.com/SpinalHDL/VexRiscv
```

---

### **Step 2: Navigate to the Plugin Directory**

```bash
cd VexRiscv/src/main/scala/vexriscv/plugin/
```

---

### **Step 3: Create Your Custom Instruction File**

Create a new Scala file named `SurfaceAreaPlugin.scala` and implement the logic to compute:

```scala
4 * 3.14 * r * r
```

---

### **Step 4: Folder Structure Example**

```
VexRiscv/
├── build.sbt
├── Makefile
├── README.md
└── src/
    └── main/
        └── scala/
            └── vexriscv/
                ├── VexRiscv.scala
                ├── VexRiscvConfigs.scala
                └── plugin/
                    ├── Decoder.scala
                    ├── RegFilePlugin.scala
                    └── SurfaceAreaPlugin.scala   ← Your custom plugin
```

---

### **Step 5: Register the Plugin in the Processor**

Edit the file:

```bash
src/main/scala/vexriscv/demo/GenCustomSimdAdd.scala
```

Add the line:

```scala
new SurfaceAreaPlugin(),
```

---

### **Step 6: Install Scala Build Tool (SBT)**

- Download from: [https://www.scala-sbt.org/download](https://www.scala-sbt.org/download)
- Install and verify with:

```bash
sbt sbtVersion
```

---

### **Step 7: Compile the Project**

```bash
sbt compile
```

This will generate: `VexRiscv.v`

---

### **Step 8: Write a C++ Testbench**

- Create a file named `main.cpp`
- Include `VVexRiscv.h`
- Instantiate the processor, provide the input `r`, and verify the output

---

### **Step 9: Install MSYS2 (Windows Only)**

- Download from: [https://www.msys2.org](https://www.msys2.org)
- Run the following:

```bash
pacman -Syu
pacman -S base-devel mingw-w64-x86_64-toolchain git
```

---

### **Step 10: Install Verilator**

```bash
pacman -S mingw-w64-x86_64-verilator
```

Compile your Verilog and C++ files:

```bash
verilator -Wall --cc VexRiscv.v --exe main.cpp
```

---

### **Step 11: Build the Simulation Code**

```bash
make -C obj_dir -f VVexRiscv.mk VVexRiscv -j
```

---

### **Step 12: Run the Simulation**

```bash
cd obj_dir
./VVexRiscv.exe
```

---

## **Notes**

✔ The computation uses **hardware multipliers**  
✔ Reads input from a register and writes result to another register

---

## **Credits**

**Prepared By:** Shaurya Bansal (23115088)  
**Topic:** Designing Custom Instruction for Surface Area of a Sphere  
**Subject:** Compiler Design Assignment

---
