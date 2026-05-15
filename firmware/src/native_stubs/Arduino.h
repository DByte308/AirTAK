#pragma once

#include <cstdint>
#include <cstddef>

#define HIGH 1
#define LOW 0
#define OUTPUT 1

uint32_t millis();
void delay(uint32_t ms);
long random(long min_value, long max_value);

inline void pinMode(int, int) {}
inline void digitalWrite(int, int) {}

template <typename T>
constexpr T min(T a, T b) { return a < b ? a : b; }

template <typename T>
constexpr T max(T a, T b) { return a > b ? a : b; }