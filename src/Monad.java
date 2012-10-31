import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

final public class Monad {

  public static <M,A> MonadI<M, List<A>> Sequence(
      final List<? extends MonadI<M, A>> _monads) {
    return new MonadI<M, List<A>>() {
      public <B> MonadI<M, B> Bind(
          final Function<List<A>, ? extends MonadI<M, B>> _f) {
        if (_monads.size() == 0) {
          return _f.call(Collections.<A>emptyList());
        }
        ArrayList<MonadI<M, A>> monadList = new ArrayList<MonadI<M, A>>();
        monadList.addAll(_monads);
        final List<A> _resultAs = new ArrayList<A>(monadList.size());
        int last = monadList.size()-1;
        MonadI<M, B> result = monadList.get(last).Bind(
          new Function<A, MonadI<M, B>>() {
            public MonadI<M, B> call(A a) {
              _resultAs.add(a);
              return _f.call(_resultAs);
            }
          }
        );
        for (int i=last-1; i>=0; i--) {
          MonadI<M, A> monad = monadList.get(i);
          final MonadI<M, B> _currResult = result;
          result = monad.Bind(new Function<A, MonadI<M, B>>() {
            public MonadI<M, B> call(A a) {
              _resultAs.add(a);
              return _currResult;
            }
          });
        }
        return result;
      }
    };
  }

  public static <M, A,B,R> MonadI<M, R> Bind2(
      MonadI<M, A> m_a,
      final MonadI<M, B> _m_b,
      final Function<Pair<A,B>, ? extends MonadI<M, R>> _f) {
    return m_a.Bind(new Function<A, MonadI<M, R>>() {
      public MonadI<M, R> call(final A _a) {
        return _m_b.Bind(new Function<B, MonadI<M, R>>() {
          public MonadI<M, R> call(B b) {
            return _f.call(new Pair<A,B>(_a,b));
          }
        });
      }
    });
  }

  public static <M, A,B,C,R> MonadI<M, R> Bind3(
      MonadI<M, A> m_a,
      final MonadI<M, B> _m_b,
      final MonadI<M, C> _m_c,
      final Function<Pair<A,Pair<B,C>>, ? extends MonadI<M, R>> _f) {
    return m_a.Bind(new Function<A, MonadI<M, R>>() {
      public MonadI<M, R> call(final A _a) {
        return _m_b.Bind(new Function<B, MonadI<M, R>>() {
          public MonadI<M, R> call(final B _b) {
            return _m_c.Bind(new Function<C, MonadI<M, R>>() {
              public MonadI<M, R> call(C c) {
                return _f.call(new Pair<A,Pair<B,C>>(_a,new Pair<B,C>(_b,c)));
              }
            });
          }
        });
      }
    });
  }
}