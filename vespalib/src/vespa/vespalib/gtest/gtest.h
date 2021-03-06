// Copyright 2019 Oath Inc. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.

#pragma once

#include <gtest/gtest.h>

/**
 * Macro for creating a main function that runs all gtests.
 */
#define GTEST_MAIN_RUN_ALL_TESTS()          \
int                                         \
main(int argc, char* argv[])                \
{                                           \
    ::testing::InitGoogleTest(&argc, argv); \
    return RUN_ALL_TESTS();                 \
}

#ifdef INSTANTIATE_TEST_SUITE_P
#define VESPA_GTEST_INSTANTIATE_TEST_SUITE_P INSTANTIATE_TEST_SUITE_P
#else
#define VESPA_GTEST_INSTANTIATE_TEST_SUITE_P INSTANTIATE_TEST_CASE_P
#endif

#ifdef TYPED_TEST_SUITE
#define VESPA_GTEST_TYPED_TEST_SUITE TYPED_TEST_SUITE
#else
#define VESPA_GTEST_TYPED_TEST_SUITE TYPED_TEST_CASE
#endif
