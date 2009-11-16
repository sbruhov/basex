package org.basex.query.up.primitives;

import java.util.Iterator;
import java.util.LinkedList;
import org.basex.data.Data;
import org.basex.query.QueryException;
import org.basex.query.item.DBNode;
import org.basex.query.item.Nod;
import org.basex.query.iter.NodIter;
import org.basex.query.up.UpdateFunctions;
import static org.basex.query.up.UpdateFunctions.*;

/**
 * Abstract update primitive which holds a copy of nodes to be inserted i.e..
 *
 * @author Workgroup DBIS, University of Konstanz 2005-09, ISC License
 * @author Lukas Kircher
 */
public abstract class NodeCopy extends UpdatePrimitive {
  /** Insertion nodes. */
  final LinkedList<NodIter> c = new LinkedList<NodIter>();
  /** Final copy of insertion nodes. */
  public Data m;

  /**
   * Constructor.
   * @param n target node
   * @param copy node copy
   */
  protected NodeCopy(final Nod n, final NodIter copy) {
    super(n);
    c.add(copy);
  }

  @Override
  public void check() throws QueryException {
    if(!(node instanceof DBNode)) return;

    if(c.size() == 0) return;
    final NodIter seq = new NodIter();
    final Iterator<NodIter> it = c.iterator();
    while(it.hasNext()) {
      final NodIter ni = it.next();
      ni.reset();
      Nod i;
      while((i = ni.next()) != null) seq.add(i);
    }
    // Text nodes still need to be merged. Two adjacent iterators in c may
    // lead to two adjacent text nodes.
    m = UpdateFunctions.buildDB(mergeText(seq), ((DBNode) node).data);
  }
}
