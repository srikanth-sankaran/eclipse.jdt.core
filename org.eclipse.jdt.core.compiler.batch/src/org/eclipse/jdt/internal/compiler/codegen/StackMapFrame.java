/*******************************************************************************
 * Copyright (c) 2005, 2010 IBM Corporation and others.
 *
 * This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License 2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package org.eclipse.jdt.internal.compiler.codegen;

import java.text.MessageFormat;
import java.util.HashMap;
import java.util.Map;
import org.eclipse.jdt.internal.compiler.lookup.Scope;
import org.eclipse.jdt.internal.compiler.lookup.TypeIds;

public class StackMapFrame {
	public static final int USED = 1;
	public static final int SAME_FRAME = 0;
	public static final int CHOP_FRAME = 1;
	public static final int APPEND_FRAME = 2;
	public static final int SAME_FRAME_EXTENDED = 3;
	public static final int FULL_FRAME = 4;
	public static final int SAME_LOCALS_1_STACK_ITEMS = 5;
	public static final int SAME_LOCALS_1_STACK_ITEMS_EXTENDED = 6;

	public int pc;
	public int numberOfStackItems;
	private int numberOfLocals;
	public int localIndex;
	public VerificationTypeInfo[] locals;
	public VerificationTypeInfo[] stackItems;
	private int numberOfDifferentLocals = -1;
	public int tagBits;

	/* Should the `next` frame (i.e., frame starting at the next bci after the last instruction of `this` frame)
	   start out congruent to `this` ? If `this` completes abruptly and there are no forward references to `next`
	   it should not!
	*/
	public boolean adoptStackShape = true;

	public StackMapFrame(int initialLocalSize) {
		this.locals = new VerificationTypeInfo[initialLocalSize];
		this.numberOfLocals = -1;
		this.numberOfDifferentLocals = -1;
	}

	public int getFrameType(StackMapFrame prevFrame) {
		final int offsetDelta = getOffsetDelta(prevFrame);
		switch (this.numberOfStackItems) {
			case 0:
				switch (numberOfDifferentLocals(prevFrame)) {
					case 0:
						return offsetDelta <= 63 ? SAME_FRAME : SAME_FRAME_EXTENDED;
					case 1:
					case 2:
					case 3:
						return APPEND_FRAME;
					case -1:
					case -2:
					case -3:
						return CHOP_FRAME;
				}
				break;
			case 1:
				switch (numberOfDifferentLocals(prevFrame)) {
					case 0:
						return offsetDelta <= 63 ? SAME_LOCALS_1_STACK_ITEMS : SAME_LOCALS_1_STACK_ITEMS_EXTENDED;
				}
		}
		return FULL_FRAME;
	}

	public void addStackItem(VerificationTypeInfo info) {
		if (info == null) {
			throw new IllegalArgumentException("info cannot be null"); //$NON-NLS-1$
		}
		if (this.stackItems == null) {
			this.stackItems = new VerificationTypeInfo[1];
			this.stackItems[0] = info;
			this.numberOfStackItems = 1;
		} else {
			final int length = this.stackItems.length;
			if (this.numberOfStackItems == length) {
				System.arraycopy(this.stackItems, 0, this.stackItems = new VerificationTypeInfo[length + 1], 0, length);
			}
			this.stackItems[this.numberOfStackItems++] = info;
		}
	}

	public StackMapFrame duplicate() {
		Map<VerificationTypeInfo, VerificationTypeInfo> cache = new HashMap<>();
		int length = this.locals.length;
		StackMapFrame result = new StackMapFrame(length);
		result.numberOfLocals = -1;
		result.numberOfDifferentLocals = -1;
		result.pc = this.pc;

		// If control doesn't flow into the new frame from `this` do not inherit the stack shape from `this`
		result.numberOfStackItems = this.adoptStackShape ? this.numberOfStackItems : 0;

		if (length != 0) {
			result.locals = new VerificationTypeInfo[length];
			for (int i = 0; i < length; i++) {
				final VerificationTypeInfo verificationTypeInfo = this.locals[i];
				result.locals[i] = getCachedValue(cache, verificationTypeInfo);
			}
		}
		length = result.numberOfStackItems;
		if (length != 0) {
			result.stackItems = new VerificationTypeInfo[length];
			for (int i = 0; i < length; i++) {
				final VerificationTypeInfo verificationTypeInfo = this.stackItems[i];
				result.stackItems[i] = getCachedValue(cache, verificationTypeInfo);
			}
		}
		return result;
	}

	private static VerificationTypeInfo getCachedValue(Map<VerificationTypeInfo, VerificationTypeInfo> cache, VerificationTypeInfo value) {
		VerificationTypeInfo cachedValue = value;
		if (value != null) {
			if (value.tag == VerificationTypeInfo.ITEM_UNINITIALIZED || value.tag == VerificationTypeInfo.ITEM_UNINITIALIZED_THIS) {
				// we use the cache only for uninitialized info
				cachedValue = cache.get(value);
				if (cachedValue == null) {
					cachedValue = value.duplicate();
					cache.put(value, cachedValue);
				}
			} else {
				cachedValue = value.duplicate();
			}
		}
		return cachedValue;
	}
	public int numberOfDifferentLocals(StackMapFrame prevFrame) {
		if (this.numberOfDifferentLocals != -1)
			return this.numberOfDifferentLocals;
		if (prevFrame == null) {
			this.numberOfDifferentLocals = 0;
			return 0;
		}
		VerificationTypeInfo[] prevLocals = prevFrame.locals;
		VerificationTypeInfo[] currentLocals = this.locals;
		int prevLocalsLength = prevLocals == null ? 0 : prevLocals.length;
		int currentLocalsLength = currentLocals == null ? 0 : currentLocals.length;
		int prevNumberOfLocals = prevFrame.getNumberOfLocals();
		int currentNumberOfLocals = getNumberOfLocals();

		int result = 0;
		if (prevNumberOfLocals == 0) {
			if (currentNumberOfLocals != 0) {
				// need to check if there is a hole in the locals
				result = currentNumberOfLocals; // append if no hole and currentNumberOfLocals <= 3
				int counter = 0;
				for (int i = 0; i < currentLocalsLength && counter < currentNumberOfLocals; i++) {
					if (currentLocals[i] != null) {
						switch (currentLocals[i].id()) {
							case TypeIds.T_double:
							case TypeIds.T_long:
								i++;
						}
						counter++;
					} else {
						result = Integer.MAX_VALUE;
						this.numberOfDifferentLocals = result;
						return result;
					}
				}
			}
		} else if (currentNumberOfLocals == 0) {
			// need to check if there is a hole in the prev locals
			int counter = 0;
			result = -prevNumberOfLocals; // chop frame if no hole and prevNumberOfLocals <= 3
			for (int i = 0; i < prevLocalsLength && counter < prevNumberOfLocals; i++) {
				if (prevLocals[i] != null) {
					switch (prevLocals[i].id()) {
						case TypeIds.T_double:
						case TypeIds.T_long:
							i++;
					}
					counter++;
				} else {
					result = Integer.MAX_VALUE;
					this.numberOfDifferentLocals = result;
					return result;
				}
			}
		} else {
			// need to see if prevLocals matches with currentLocals
			int indexInPrevLocals = 0;
			int indexInCurrentLocals = 0;
			int currentLocalsCounter = 0;
			int prevLocalsCounter = 0;
			currentLocalsLoop: for (; indexInCurrentLocals < currentLocalsLength
					&& currentLocalsCounter < currentNumberOfLocals; indexInCurrentLocals++) {
				VerificationTypeInfo currentLocal = currentLocals[indexInCurrentLocals];
				if (currentLocal != null) {
					currentLocalsCounter++;
					switch (currentLocal.id()) {
						case TypeIds.T_double:
						case TypeIds.T_long:
							indexInCurrentLocals++; // next entry is null
					}
				}
				if (indexInPrevLocals < prevLocalsLength && prevLocalsCounter < prevNumberOfLocals) {
					VerificationTypeInfo prevLocal = prevLocals[indexInPrevLocals];
					if (prevLocal != null) {
						prevLocalsCounter++;
						switch (prevLocal.id()) {
							case TypeIds.T_double:
							case TypeIds.T_long:
								indexInPrevLocals++; // next entry is null
						}
					}
					// now we need to check if prevLocal matches with currentLocal
					// the index must be the same
					if (equals(prevLocal, currentLocal) && indexInPrevLocals == indexInCurrentLocals) {
						if (result != 0) {
							result = Integer.MAX_VALUE;
							this.numberOfDifferentLocals = result;
							return result;
						}
					} else {
						// locals at the same location are not equals - this has to be a full frame
						result = Integer.MAX_VALUE;
						this.numberOfDifferentLocals = result;
						return result;
					}
					indexInPrevLocals++;
					continue currentLocalsLoop;
				}
				// process remaining current locals
				if (currentLocal != null) {
					result++;
				} else {
					result = Integer.MAX_VALUE;
					this.numberOfDifferentLocals = result;
					return result;
				}
				indexInCurrentLocals++;
				break currentLocalsLoop;
			}
			if (currentLocalsCounter < currentNumberOfLocals) {
				for (; indexInCurrentLocals < currentLocalsLength
						&& currentLocalsCounter < currentNumberOfLocals; indexInCurrentLocals++) {
					VerificationTypeInfo currentLocal = currentLocals[indexInCurrentLocals];
					if (currentLocal == null) {
						result = Integer.MAX_VALUE;
						this.numberOfDifferentLocals = result;
						return result;
					}
					result++;
					currentLocalsCounter++;
					switch (currentLocal.id()) {
						case TypeIds.T_double:
						case TypeIds.T_long:
							indexInCurrentLocals++; // next entry is null
					}
				}
			} else if (prevLocalsCounter < prevNumberOfLocals) {
				result = -result;
				// process possible remaining prev locals
				for (; indexInPrevLocals < prevLocalsLength
						&& prevLocalsCounter < prevNumberOfLocals; indexInPrevLocals++) {
					VerificationTypeInfo prevLocal = prevLocals[indexInPrevLocals];
					if (prevLocal == null) {
						result = Integer.MAX_VALUE;
						this.numberOfDifferentLocals = result;
						return result;
					}
					result--;
					prevLocalsCounter++;
					switch (prevLocal.id()) {
						case TypeIds.T_double:
						case TypeIds.T_long:
							indexInPrevLocals++; // next entry is null
					}
				}
			}
		}
		this.numberOfDifferentLocals = result;
		return result;
	}

	public int getNumberOfLocals() {
		if (this.numberOfLocals != -1) {
			return this.numberOfLocals;
		}
		int result = 0;
		final int length = this.locals == null ? 0 : this.locals.length;
		for (int i = 0; i < length; i++) {
			if (this.locals[i] != null) {
				switch (this.locals[i].id()) {
					case TypeIds.T_double:
					case TypeIds.T_long:
						i++;
				}
				result++;
			}
		}
		this.numberOfLocals = result;
		return result;
	}

	public int getOffsetDelta(StackMapFrame prevFrame) {
		if (prevFrame == null)
			return this.pc;
		return prevFrame.pc == -1 ? this.pc : this.pc - prevFrame.pc - 1;
	}

	@Override
	public String toString() {
		StringBuilder buffer = new StringBuilder();
		printFrame(buffer, this);
		return String.valueOf(buffer);
	}

	private void printFrame(StringBuilder buffer, StackMapFrame frame) {
		String pattern = "[pc : {0} locals: {1} stack items: {2}\nlocals: {3}\nstack: {4}\n]"; //$NON-NLS-1$
		int localsLength = frame.locals == null ? 0 : frame.locals.length;
		buffer.append(MessageFormat.format(pattern,
				new Object[] { Integer.toString(frame.pc), Integer.toString(frame.getNumberOfLocals()),
						Integer.toString(frame.numberOfStackItems), print(frame.locals, localsLength),
						print(frame.stackItems, frame.numberOfStackItems) }));
	}

	private String print(VerificationTypeInfo[] infos, int length) {
		StringBuilder buffer = new StringBuilder();
		buffer.append('[');
		if (infos != null) {
			for (int i = 0; i < length; i++) {
				if (i != 0)
					buffer.append(',');
				VerificationTypeInfo verificationTypeInfo = infos[i];
				if (verificationTypeInfo == null) {
					buffer.append("top"); //$NON-NLS-1$
					continue;
				}
				buffer.append(verificationTypeInfo);
			}
		}
		buffer.append(']');
		return String.valueOf(buffer);
	}

	public void putLocal(int resolvedPosition, VerificationTypeInfo info) {
		if (this.locals == null) {
			this.locals = new VerificationTypeInfo[resolvedPosition + 1];
			this.locals[resolvedPosition] = info;
		} else {
			final int length = this.locals.length;
			if (resolvedPosition >= length) {
				System.arraycopy(this.locals, 0, this.locals = new VerificationTypeInfo[resolvedPosition + 1], 0,
						length);
			}
			this.locals[resolvedPosition] = info;
		}
	}

	public void replaceWithElementType() {
		VerificationTypeInfo info = this.stackItems[this.numberOfStackItems - 1];
		VerificationTypeInfo info2 = info.duplicate();
		info2.replaceWithElementType();
		this.stackItems[this.numberOfStackItems - 1] = info2;
	}

	public int getIndexOfDifferentLocals(int differentLocalsCount) {
		for (int i = this.locals.length - 1; i >= 0; i--) {
			VerificationTypeInfo currentLocal = this.locals[i];
			if (currentLocal == null) {
				// check the previous slot
				continue;
			} else {
				differentLocalsCount--;
			}
			if (differentLocalsCount == 0) {
				return i;
			}
		}
		return 0;
	}

	private static boolean equals(VerificationTypeInfo info, VerificationTypeInfo info2) {
		if (info == null) {
			return info2 == null;
		}
		if (info2 == null)
			return false;
		return info.equals(info2);
	}

	public StackMapFrame merge(StackMapFrame frame, Scope scope) {
		if (frame.pc == -1) {
			// branching
			return this;
		}
		if (this.numberOfStackItems == frame.numberOfStackItems) {
			for (int i = 0, max = this.numberOfStackItems; i < max; i++) {
				this.stackItems[i] = this.stackItems[i].merge(frame.stackItems[i], scope);
			}
		}
		return this;
	}
}
