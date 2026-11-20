cmake_minimum_required(VERSION 3.22.1)
project(carptocar_oboe_resampler LANGUAGES CXX)

# Standalone resampler from google/oboe 1.11.0, src/flowgraph/resampler.
add_library(carptocar_oboe_resampler SHARED
    OboeSpeechResampler.cpp
    oboe/IntegerRatio.cpp
    oboe/LinearResampler.cpp
    oboe/MultiChannelResampler.cpp
    oboe/PolyphaseResampler.cpp
    oboe/PolyphaseResamplerMono.cpp
    oboe/PolyphaseResamplerStereo.cpp
    oboe/SincResampler.cpp
    oboe/SincResamplerStereo.cpp
)
target_compile_features(carptocar_oboe_resampler PRIVATE cxx_std_17)
target_compile_definitions(carptocar_oboe_resampler PRIVATE RESAMPLER_OUTER_NAMESPACE=oboe)
# Q4 road tests use a Debug APK, including this real-time audio loop.
target_compile_options(carptocar_oboe_resampler PRIVATE -O2)
