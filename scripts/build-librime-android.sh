#!/bin/bash
# Needs: Android NDK 26.1.10909125 + cmake 3.22.1 under /opt/android-sdk; deps cloned/extracted in /home/user/rime/deps
# (glog yaml-cpp leveldb marisa-trie OpenCC, boost_1_84_0), librime in /home/user/rime/librime. Output: $PREFIX (/home/user/rime/prefix).
# Cross-compile librime + deps for Android arm64-v8a into $PREFIX. Usage: build_android.sh
set -ex
R=/home/user/rime; D=$R/deps; PREFIX=$R/prefix; NDK=/opt/android-sdk/ndk/26.1.10909125
CM=/opt/android-sdk/cmake/3.22.1/bin/cmake; NINJA=/opt/android-sdk/cmake/3.22.1/bin/ninja
export PATH=$(dirname $CM):$PATH
COMMON=(-G Ninja -DCMAKE_TOOLCHAIN_FILE=$NDK/build/cmake/android.toolchain.cmake -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-26
  -DANDROID_STL=c++_static -DCMAKE_BUILD_TYPE=Release -DCMAKE_INSTALL_PREFIX=$PREFIX -DCMAKE_PREFIX_PATH=$PREFIX
  -DCMAKE_FIND_ROOT_PATH=$PREFIX -DBUILD_SHARED_LIBS=OFF -DCMAKE_POSITION_INDEPENDENT_CODE=ON -DCMAKE_POLICY_VERSION_MINIMUM=3.5)
mkdir -p $PREFIX
[ -d $D/boost_1_84_0 ] || tar -xzf $D/boost.tar.gz -C $D
b() { # name srcdir extra...
  local n=$1 s=$2; shift 2
  [ -f $R/stamp.$n ] && return 0
  $CM -S $s -B $R/build/$n "${COMMON[@]}" "$@"
  $CM --build $R/build/$n -j8; $CM --install $R/build/$n
  touch $R/stamp.$n
}
b glog $D/glog -DWITH_GFLAGS=OFF -DBUILD_TESTING=OFF -DWITH_GTEST=OFF -DWITH_UNWIND=OFF
b yaml-cpp $D/yaml-cpp -DYAML_CPP_BUILD_TESTS=OFF -DYAML_CPP_BUILD_TOOLS=OFF -DYAML_CPP_BUILD_CONTRIB=OFF
b leveldb $D/leveldb -DLEVELDB_BUILD_TESTS=OFF -DLEVELDB_BUILD_BENCHMARKS=OFF
b marisa $D/marisa-trie -DENABLE_TOOLS=OFF -DBUILD_TESTING=OFF
b opencc $D/OpenCC -DOPENCC_BUILD_TOOLS=OFF -DENABLE_GTEST=OFF -DBUILD_DOCUMENTATION=OFF -DOPENCC_ENABLE_INSTALL=ON
b librime $R/librime -DBUILD_SHARED_LIBS=ON -DBUILD_STATIC=ON -DBUILD_TEST=OFF -DBUILD_DATA=OFF -DENABLE_LOGGING=ON \
  -DCMAKE_SHARED_LINKER_FLAGS=-llog -DCMAKE_EXE_LINKER_FLAGS=-llog -DBoost_INCLUDE_DIR=$D/boost_1_84_0 -DBOOST_ROOT=$D/boost_1_84_0 -DBoost_NO_SYSTEM_PATHS=ON
echo ALL-DONE
