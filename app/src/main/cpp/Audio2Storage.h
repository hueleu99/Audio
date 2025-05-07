//
// Created by ducng on 5/7/2025.
//

#ifndef AUDIODETECTOR_AUDIO2STORAGE_H
#define AUDIODETECTOR_AUDIO2STORAGE_H


#pragma once
#include <vector>
#include <mutex>

class Audio2Storage {
public:
    void append(const int16_t* data, int samples, int64_t ptsUs);
    std::vector<int16_t> getRange(int64_t startUs, int64_t endUs, int sampleRate, int channels);

private:
    struct PCMItem {
        int64_t ptsUs;
        std::vector<int16_t> data;
    };

    std::vector<PCMItem> buffer;
    std::mutex mutex;
};



#endif //AUDIODETECTOR_AUDIO2STORAGE_H
