package edu.columbia.cs.psl.phosphor.struct;

import edu.columbia.cs.psl.phosphor.runtime.LazyArrayIntTags;
import edu.columbia.cs.psl.phosphor.struct.multid.MultiDTaintedLongArrayWithIntTag;

public class TaintedLongArrayWithIntTag extends TaintedPrimitiveArrayWithIntTag {
	public long[] val;
	
	@Override
	public Object toStackType() {
		return new MultiDTaintedLongArrayWithIntTag(new LazyArrayIntTags(taint), val);
	}
	public TaintedLongArrayWithIntTag()
	{
		
	}
	public TaintedLongArrayWithIntTag(int[] taint, long[] val)
	{
		this.taint=taint;
		this.val=val;
	}
}
