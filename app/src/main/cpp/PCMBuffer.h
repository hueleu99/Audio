//
// Created by ducng on 5/6/2025.
//

#ifndef AUDIODETECTOR_PCMBUFFER_H
#define AUDIODETECTOR_PCMBUFFER_H
#pragma once
#include <vector>
#include <cstdint>

class PCMBuffer {
public:
    std::vector<int16_t> data;
    int64_t ptsUs;

    void clear() {
        data.clear();
        ptsUs = 0;
    }
};

#endif //AUDIODETECTOR_PCMBUFFER_H
