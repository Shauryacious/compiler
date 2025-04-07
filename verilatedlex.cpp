#include <iostream>
#include <cmath>

double calculate_sphere_volume(double radius) {
    return (4.0/3.0) * M_PI * pow(radius, 3);
}

int read_result(double volume) {
    // Convert the floating point volume to integer representation
    return static_cast<int>(volume);
}