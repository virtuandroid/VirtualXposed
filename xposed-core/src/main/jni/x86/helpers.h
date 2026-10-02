#ifndef VIRTUALXPOSED_HELPERS_H
#define VIRTUALXPOSED_HELPERS_H


class helpers {
public:
    static int InstallHook(void *target, void *hooker, void **original = nullptr);
};


#endif //VIRTUALXPOSED_HELPERS_H
