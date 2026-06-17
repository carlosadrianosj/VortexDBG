package com.vortexdbg.hook.hookzz

import com.vortexdbg.arm.context.EditableArm32RegisterContext
import com.vortexdbg.hook.InvocationContext

interface HookZzArm32RegisterContext : EditableArm32RegisterContext, InvocationContext
