package rs117.hd.utils;

import java.util.function.Function;
import lombok.RequiredArgsConstructor;

public class ExpressionParserEvaluators {
	@RequiredArgsConstructor
	public static final class ConstantFunction implements Function<VariableSupplier, Object> {
		private final Object value;

		@Override
		public Object apply(VariableSupplier vars) { return value; }
	}

	@RequiredArgsConstructor
	public static final class ObjectVariableFunction implements Function<VariableSupplier, Object> {
		private final String key;

		@Override
		public Object apply(VariableSupplier vars) { return vars.get(key); }
	}

	@RequiredArgsConstructor
	public static final class IntToObjectFunction implements Function<VariableSupplier, Object> {
		private final ExpressionParser.IntEval eval;

		@Override
		public Object apply(VariableSupplier vars) { return eval.apply(vars); }
	}

	@RequiredArgsConstructor
	public static final class FloatToObjectFunction implements Function<VariableSupplier, Object> {
		private final ExpressionParser.FloatEval eval;

		@Override
		public Object apply(VariableSupplier vars) { return eval.apply(vars); }
	}

	@RequiredArgsConstructor
	public static final class BooleanToObjectFunction implements Function<VariableSupplier, Object> {
		private final ExpressionParser.BooleanEval eval;

		@Override
		public Object apply(VariableSupplier vars) { return eval.apply(vars); }
	}

	@RequiredArgsConstructor
	public static final class BooleanEvalPredicate implements ExpressionPredicate {
		private final ExpressionParser.BooleanEval eval;

		@Override
		public boolean test(VariableSupplier vars) { return eval.apply(vars); }
	}

	@RequiredArgsConstructor
	public static final class ObjectTernaryFunction implements Function<VariableSupplier, Object> {
		private final ExpressionParser.BooleanEval condition;
		private final Function<VariableSupplier, Object> ifTrue, ifFalse;

		@Override
		public Object apply(VariableSupplier vars) { return condition.apply(vars) ? ifTrue.apply(vars) : ifFalse.apply(vars); }
	}

	@RequiredArgsConstructor
	public static final class IntConstant implements ExpressionParser.IntEval {
		private final int value;

		@Override
		public int apply(VariableSupplier vars) { return value; }
	}

	@RequiredArgsConstructor
	public static final class IntVariable implements ExpressionParser.IntEval {
		private final String key;

		@Override
		public int apply(VariableSupplier vars) { return vars.getInt(key); }
	}

	@RequiredArgsConstructor
	public static final class FloatConstant implements ExpressionParser.FloatEval {
		private final float value;

		@Override
		public float apply(VariableSupplier vars) { return value; }
	}

	@RequiredArgsConstructor
	public static final class FloatVariable implements ExpressionParser.FloatEval {
		private final String key;

		@Override
		public float apply(VariableSupplier vars) { return vars.getFloat(key); }
	}

	@RequiredArgsConstructor
	public static final class BooleanConstant implements ExpressionParser.BooleanEval {
		private final boolean value;

		@Override
		public boolean apply(VariableSupplier vars) { return value; }
	}

	@RequiredArgsConstructor
	public static final class BooleanVariable implements ExpressionParser.BooleanEval {
		private final String key;

		@Override
		public boolean apply(VariableSupplier vars) { return vars.getBoolean(key); }
	}

	@RequiredArgsConstructor
	public static final class IntAdd implements ExpressionParser.IntEval {
		private final ExpressionParser.IntEval l, r;

		@Override
		public int apply(VariableSupplier vars) { return l.apply(vars) + r.apply(vars); }
	}

	@RequiredArgsConstructor
	public static final class IntSub implements ExpressionParser.IntEval {
		private final ExpressionParser.IntEval l, r;

		@Override
		public int apply(VariableSupplier vars) { return l.apply(vars) - r.apply(vars); }
	}

	@RequiredArgsConstructor
	public static final class IntMul implements ExpressionParser.IntEval {
		private final ExpressionParser.IntEval l, r;

		@Override
		public int apply(VariableSupplier vars) { return l.apply(vars) * r.apply(vars); }
	}

	@RequiredArgsConstructor
	public static final class IntDiv implements ExpressionParser.IntEval {
		private final ExpressionParser.IntEval l, r;

		@Override
		public int apply(VariableSupplier vars) { return l.apply(vars) / r.apply(vars); }
	}

	@RequiredArgsConstructor
	public static final class IntMod implements ExpressionParser.IntEval {
		private final ExpressionParser.IntEval l, r;

		@Override
		public int apply(VariableSupplier vars) { return l.apply(vars) % r.apply(vars); }
	}

	@RequiredArgsConstructor
	public static final class IntTernary implements ExpressionParser.IntEval {
		private final ExpressionParser.BooleanEval condition;
		private final ExpressionParser.IntEval ifTrue, ifFalse;

		@Override
		public int apply(VariableSupplier vars) { return condition.apply(vars) ? ifTrue.apply(vars) : ifFalse.apply(vars); }
	}

	@RequiredArgsConstructor
	public static final class FloatAdd implements ExpressionParser.FloatEval {
		private final ExpressionParser.FloatEval l, r;

		@Override
		public float apply(VariableSupplier vars) { return l.apply(vars) + r.apply(vars); }
	}

	@RequiredArgsConstructor
	public static final class FloatSub implements ExpressionParser.FloatEval {
		private final ExpressionParser.FloatEval l, r;

		@Override
		public float apply(VariableSupplier vars) { return l.apply(vars) - r.apply(vars); }
	}

	@RequiredArgsConstructor
	public static final class FloatMul implements ExpressionParser.FloatEval {
		private final ExpressionParser.FloatEval l, r;

		@Override
		public float apply(VariableSupplier vars) { return l.apply(vars) * r.apply(vars); }
	}

	@RequiredArgsConstructor
	public static final class FloatDiv implements ExpressionParser.FloatEval {
		private final ExpressionParser.FloatEval l, r;

		@Override
		public float apply(VariableSupplier vars) { return l.apply(vars) / r.apply(vars); }
	}

	@RequiredArgsConstructor
	public static final class FloatMod implements ExpressionParser.FloatEval {
		private final ExpressionParser.FloatEval l, r;

		@Override
		public float apply(VariableSupplier vars) { return l.apply(vars) % r.apply(vars); }
	}

	@RequiredArgsConstructor
	public static final class FloatTernary implements ExpressionParser.FloatEval {
		private final ExpressionParser.BooleanEval condition;
		private final ExpressionParser.FloatEval ifTrue, ifFalse;

		@Override
		public float apply(VariableSupplier vars) { return condition.apply(vars) ? ifTrue.apply(vars) : ifFalse.apply(vars); }
	}

	@RequiredArgsConstructor
	public static final class BooleanAnd implements ExpressionParser.BooleanEval {
		private final ExpressionParser.BooleanEval l, r;

		@Override
		public boolean apply(VariableSupplier vars) { return l.apply(vars) && r.apply(vars); }
	}

	@RequiredArgsConstructor
	public static final class BooleanOr implements ExpressionParser.BooleanEval {
		private final ExpressionParser.BooleanEval l, r;

		@Override
		public boolean apply(VariableSupplier vars) { return l.apply(vars) || r.apply(vars); }
	}

	@RequiredArgsConstructor
	public static final class BooleanNot implements ExpressionParser.BooleanEval {
		private final ExpressionParser.BooleanEval operand;

		@Override
		public boolean apply(VariableSupplier vars) { return !operand.apply(vars); }
	}

	@RequiredArgsConstructor
	public static final class IntLess implements ExpressionParser.BooleanEval {
		private final ExpressionParser.IntEval l, r;

		@Override
		public boolean apply(VariableSupplier vars) { return l.apply(vars) < r.apply(vars); }
	}

	@RequiredArgsConstructor
	public static final class IntLessEqual implements ExpressionParser.BooleanEval {
		private final ExpressionParser.IntEval l, r;

		@Override
		public boolean apply(VariableSupplier vars) { return l.apply(vars) <= r.apply(vars); }
	}

	@RequiredArgsConstructor
	public static final class IntGreater implements ExpressionParser.BooleanEval {
		private final ExpressionParser.IntEval l, r;

		@Override
		public boolean apply(VariableSupplier vars) { return l.apply(vars) > r.apply(vars); }
	}

	@RequiredArgsConstructor
	public static final class IntGreaterEqual implements ExpressionParser.BooleanEval {
		private final ExpressionParser.IntEval l, r;

		@Override
		public boolean apply(VariableSupplier vars) { return l.apply(vars) >= r.apply(vars); }
	}

	@RequiredArgsConstructor
	public static final class IntEqual implements ExpressionParser.BooleanEval {
		private final ExpressionParser.IntEval l, r;

		@Override
		public boolean apply(VariableSupplier vars) { return l.apply(vars) == r.apply(vars); }
	}

	@RequiredArgsConstructor
	public static final class IntNotEqual implements ExpressionParser.BooleanEval {
		private final ExpressionParser.IntEval l, r;

		@Override
		public boolean apply(VariableSupplier vars) { return l.apply(vars) != r.apply(vars); }
	}

	@RequiredArgsConstructor
	public static final class FloatLess implements ExpressionParser.BooleanEval {
		private final ExpressionParser.FloatEval l, r;

		@Override
		public boolean apply(VariableSupplier vars) { return l.apply(vars) < r.apply(vars); }
	}

	@RequiredArgsConstructor
	public static final class FloatLessEqual implements ExpressionParser.BooleanEval {
		private final ExpressionParser.FloatEval l, r;

		@Override
		public boolean apply(VariableSupplier vars) { return l.apply(vars) <= r.apply(vars); }
	}

	@RequiredArgsConstructor
	public static final class FloatGreater implements ExpressionParser.BooleanEval {
		private final ExpressionParser.FloatEval l, r;

		@Override
		public boolean apply(VariableSupplier vars) { return l.apply(vars) > r.apply(vars); }
	}

	@RequiredArgsConstructor
	public static final class FloatGreaterEqual implements ExpressionParser.BooleanEval {
		private final ExpressionParser.FloatEval l, r;

		@Override
		public boolean apply(VariableSupplier vars) { return l.apply(vars) >= r.apply(vars); }
	}

	@RequiredArgsConstructor
	public static final class FloatEqual implements ExpressionParser.BooleanEval {
		private final ExpressionParser.FloatEval l, r;

		@Override
		public boolean apply(VariableSupplier vars) { return l.apply(vars) == r.apply(vars); }
	}

	@RequiredArgsConstructor
	public static final class FloatNotEqual implements ExpressionParser.BooleanEval {
		private final ExpressionParser.FloatEval l, r;

		@Override
		public boolean apply(VariableSupplier vars) { return l.apply(vars) != r.apply(vars); }
	}

	@RequiredArgsConstructor
	public static final class BooleanEqual implements ExpressionParser.BooleanEval {
		private final ExpressionParser.BooleanEval l, r;

		@Override
		public boolean apply(VariableSupplier vars) { return l.apply(vars) == r.apply(vars); }
	}

	@RequiredArgsConstructor
	public static final class BooleanNotEqual implements ExpressionParser.BooleanEval {
		private final ExpressionParser.BooleanEval l, r;

		@Override
		public boolean apply(VariableSupplier vars) { return l.apply(vars) != r.apply(vars); }
	}

	@RequiredArgsConstructor
	public static final class BooleanTernary implements ExpressionParser.BooleanEval {
		private final ExpressionParser.BooleanEval condition, ifTrue, ifFalse;

		@Override
		public boolean apply(VariableSupplier vars) { return condition.apply(vars) ? ifTrue.apply(vars) : ifFalse.apply(vars); }
	}
}
