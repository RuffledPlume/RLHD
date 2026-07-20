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
	public static final class IntTernary implements ExpressionParser.IntEval {
		private final ExpressionParser.BooleanEval condition;
		private final ExpressionParser.IntEval ifTrue, ifFalse;

		@Override
		public int apply(VariableSupplier vars) { return condition.apply(vars) ? ifTrue.apply(vars) : ifFalse.apply(vars); }
	}

	@RequiredArgsConstructor
	public static final class FloatTernary implements ExpressionParser.FloatEval {
		private final ExpressionParser.BooleanEval condition;
		private final ExpressionParser.FloatEval ifTrue, ifFalse;

		@Override
		public float apply(VariableSupplier vars) { return condition.apply(vars) ? ifTrue.apply(vars) : ifFalse.apply(vars); }
	}

	@RequiredArgsConstructor
	public static final class BooleanTernary implements ExpressionParser.BooleanEval {
		private final ExpressionParser.BooleanEval condition, ifTrue, ifFalse;

		@Override
		public boolean apply(VariableSupplier vars) { return condition.apply(vars) ? ifTrue.apply(vars) : ifFalse.apply(vars); }
	}

	@RequiredArgsConstructor
	public static final class IntMathOperation implements ExpressionParser.IntEval {
		private final ExpressionParser.Operator op;
		private final ExpressionParser.IntEval l, r;

		@Override
		public int apply(VariableSupplier vars) {
			final int lVal = l.apply(vars);
			final int rVal = r.apply(vars);

			switch (op) {
				case ADD:
					return lVal + rVal;
				case SUB:
					return lVal - rVal;
				case MUL:
					return lVal * rVal;
				case DIV:
					return lVal / rVal;
				case MOD:
					return lVal % rVal;
			}

			throw new UnsupportedOperationException("Operator '" + op + "' is not a math operator");
		}
	}

	@RequiredArgsConstructor
	public static final class FloatMathOperation implements ExpressionParser.FloatEval {
		private final ExpressionParser.Operator op;
		private final ExpressionParser.FloatEval l, r;

		@Override
		public float apply(VariableSupplier vars) {
			final float lVal = l.apply(vars);
			final float rVal = r.apply(vars);

			switch (op) {
				case ADD:
					return lVal + rVal;
				case SUB:
					return lVal - rVal;
				case MUL:
					return lVal * rVal;
				case DIV:
					return lVal / rVal;
				case MOD:
					return lVal % rVal;
			}

			throw new UnsupportedOperationException("Operator '" + op + "' is not a math operator");
		}
	}

	@RequiredArgsConstructor
	public static final class BooleanComparisons implements ExpressionParser.BooleanEval {
		private final ExpressionParser.Operator op;
		private final ExpressionParser.BooleanEval l, r;

		@Override
		public boolean apply(VariableSupplier vars) {
			final boolean lVal = l.apply(vars);
			final boolean rVal = r.apply(vars);
			switch (op) {
				case AND:
					return lVal && rVal;
				case OR:
					return lVal || rVal;
				case EQUAL:
					return lVal == rVal;
				case NOTEQUAL:
					return lVal != rVal;
			}

			throw new UnsupportedOperationException("Operator '" + op + "' is not a boolean comparison operator");
		}
	}

	@RequiredArgsConstructor
	public static final class BooleanNot implements ExpressionParser.BooleanEval {
		private final ExpressionParser.BooleanEval operand;

		@Override
		public boolean apply(VariableSupplier vars) { return !operand.apply(vars); }
	}

	@RequiredArgsConstructor
	public static final class IntComparisons implements ExpressionParser.BooleanEval {
		private final ExpressionParser.Operator op;
		private final ExpressionParser.IntEval l, r;

		@Override
		public boolean apply(VariableSupplier vars) {
			final int lVal = l.apply(vars);
			final int rVal = r.apply(vars);
			switch (op) {
				case LESS:
					return lVal < rVal;
				case LEQUAL:
					return lVal <= rVal;
				case GREATER:
					return lVal > rVal;
				case GEQUAL:
					return lVal >= rVal;
				case EQUAL:
					return lVal == rVal;
				case NOTEQUAL:
					return lVal != rVal;
			}

			throw new UnsupportedOperationException("Operator '" + op + "' is not a int comparison operator");
		}
	}

	@RequiredArgsConstructor
	public static final class FloatComparisons implements ExpressionParser.BooleanEval {
		private final ExpressionParser.Operator op;
		private final ExpressionParser.FloatEval l, r;

		@Override
		public boolean apply(VariableSupplier vars) {
			final float lVal = l.apply(vars);
			final float rVal = r.apply(vars);
			switch (op) {
				case LESS:
					return lVal < rVal;
				case LEQUAL:
					return lVal <= rVal;
				case GREATER:
					return lVal > rVal;
				case GEQUAL:
					return lVal >= rVal;
				case EQUAL:
					return lVal == rVal;
				case NOTEQUAL:
					return lVal != rVal;
			}

			throw new UnsupportedOperationException("Operator '" + op + "' is not a int comparison operator");
		}
	}
}
