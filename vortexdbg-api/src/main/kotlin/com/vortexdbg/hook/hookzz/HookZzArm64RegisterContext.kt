package com.vortexdbg.hook.hookzz

import com.vortexdbg.arm.context.EditableArm64RegisterContext
import com.vortexdbg.hook.InvocationContext

interface HookZzArm64RegisterContext : EditableArm64RegisterContext, InvocationContext
