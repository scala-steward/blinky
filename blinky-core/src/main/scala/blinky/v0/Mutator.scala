package blinky.v0

import blinky.v0.Mutator._
import blinky.v0.ReplaceType._
import scalafix.v1._

import scala.annotation.tailrec
import scala.meta._

trait MutatorGroup {
  def groupName: String

  def getSubMutators: List[Mutator]

  abstract class SimpleMutator(simpleName: String) extends Mutator {
    override val name = s"$groupName.$simpleName"
  }
}

trait Mutator {
  def name: String

  def getMutator(implicit doc: SemanticDocument): MutationResult
}

object Mutator {
  abstract class NonGroupedMutator(override val name: String) extends Mutator

  type MutationResult = PartialFunction[Term, ReplaceType]

  private val allGroups: List[MutatorGroup] =
    List(
      ArithmeticOperators,
      ConditionalExpressions,
      LiteralStrings,
      ScalaOptions,
      ScalaTry,
      Collections,
      PartialFunctions,
      ScalaStrings
    )

  val all: Map[String, Mutator] =
    Map(
      LiteralBooleans.name -> LiteralBooleans
    ) ++
      allGroups.flatMap(group => group.getSubMutators.map(mutator => (mutator.name, mutator)))

  def findMutators(str: String): List[Mutator] =
    all.collect {
      case (name, mutation) if name == str                => mutation
      case (name, mutation) if name.startsWith(str + ".") => mutation
    }.toList

  object LiteralBooleans extends NonGroupedMutator("LiteralBooleans") {
    override def getMutator(implicit doc: SemanticDocument): MutationResult = {
      case Lit.Boolean(value) =>
        default(Lit.Boolean(!value))
    }
  }

  object ArithmeticOperators extends MutatorGroup {
    override val groupName: String = "ArithmeticOperators"

    override val getSubMutators: List[Mutator] =
      List(IntPlusToMinus, IntMinusToPlus, IntMulToDiv, IntDivToMul)

    object IntPlusToMinus extends SimpleMutator("IntPlusToMinus") {
      override def getMutator(implicit doc: SemanticDocument): MutationResult = {
        case plus @ Term.ApplyInfix(left, Term.Name("+"), targs, right)
            if SymbolMatcher.exact("scala/Int#`+`(+4).").matches(plus.symbol) =>
          default(Term.ApplyInfix(left, Term.Name("-"), targs, right))
      }
    }

    object IntMinusToPlus extends SimpleMutator("IntMinusToPlus") {
      override def getMutator(implicit doc: SemanticDocument): MutationResult = {
        case minus @ Term.ApplyInfix(left, Term.Name("-"), targs, right)
            if SymbolMatcher.exact("scala/Int#`-`(+3).").matches(minus.symbol) =>
          default(Term.ApplyInfix(left, Term.Name("+"), targs, right))
      }
    }

    object IntMulToDiv extends SimpleMutator("IntMulToDiv") {
      override def getMutator(implicit doc: SemanticDocument): MutationResult = {
        case mul @ Term.ApplyInfix(left, Term.Name("*"), targs, right)
            if SymbolMatcher.exact("scala/Int#`*`(+3).").matches(mul.symbol) =>
          default(Term.ApplyInfix(left, Term.Name("/"), targs, right))
      }
    }

    object IntDivToMul extends SimpleMutator("IntDivToMul") {
      override def getMutator(implicit doc: SemanticDocument): MutationResult = {
        case div @ Term.ApplyInfix(left, Term.Name("/"), targs, right)
            if SymbolMatcher.exact("scala/Int#`/`(+3).").matches(div.symbol) =>
          default(Term.ApplyInfix(left, Term.Name("*"), targs, right))
      }
    }
  }

  object ConditionalExpressions extends MutatorGroup {
    override val groupName: String = "ConditionalExpressions"

    override val getSubMutators: List[Mutator] =
      List(AndToOr, OrToAnd, RemoveUnaryNot)

    object AndToOr extends SimpleMutator("AndToOr") {
      override def getMutator(implicit doc: SemanticDocument): MutationResult = {
        case and @ Term.ApplyInfix(left, Term.Name("&&"), targs, right)
            if SymbolMatcher.exact("scala/Boolean#`&&`().").matches(and.symbol) =>
          default(Term.ApplyInfix(left, Term.Name("||"), targs, right))
      }
    }

    object OrToAnd extends SimpleMutator("OrToAnd") {
      override def getMutator(implicit doc: SemanticDocument): MutationResult = {
        case or @ Term.ApplyInfix(left, Term.Name("||"), targs, right)
            if SymbolMatcher.exact("scala/Boolean#`||`().").matches(or.symbol) =>
          default(Term.ApplyInfix(left, Term.Name("&&"), targs, right))
      }
    }

    object RemoveUnaryNot extends SimpleMutator("RemoveUnaryNot") {
      override def getMutator(implicit doc: SemanticDocument): MutationResult = {
        case boolNeg @ Term.ApplyUnary(Term.Name("!"), arg)
            if SymbolMatcher.exact("scala/Boolean#`unary_!`().").matches(boolNeg.symbol) =>
          default(arg)
      }
    }
  }

  object LiteralStrings extends MutatorGroup {
    override val groupName: String = "LiteralStrings"

    override val getSubMutators: List[Mutator] =
      List(
        EmptyToMutated,
        EmptyInterToMutated,
        NonEmptyToMutated,
        NonEmptyInterToMutated
      )

    object EmptyToMutated extends SimpleMutator("EmptyToMutated") {
      override def getMutator(implicit doc: SemanticDocument): MutationResult = {
        case Lit.String(value) if value.isEmpty =>
          default(Lit.String("mutated!"))
      }
    }

    object EmptyInterToMutated extends SimpleMutator("EmptyInterToMutated") {
      override def getMutator(implicit doc: SemanticDocument): MutationResult = {
        case Term.Interpolate(Term.Name("s" | "f" | "raw"), List(Lit.String("")), List()) =>
          default(Lit.String("mutated!"))
      }
    }

    object NonEmptyToMutated extends SimpleMutator("NonEmptyToMutated") {
      override def getMutator(implicit doc: SemanticDocument): MutationResult = {
        case Lit.String(value) if value.nonEmpty =>
          default(Lit.String(""), Lit.String("mutated!"))
      }
    }

    object NonEmptyInterToMutated extends SimpleMutator("NonEmptyInterToMutated") {
      override def getMutator(implicit doc: SemanticDocument): MutationResult = {
        case Term.Interpolate(Term.Name("s" | "f" | "raw"), lits, names)
            if names.nonEmpty || lits.exists { case Lit.String(str) =>
              str.nonEmpty
            } =>
          default(Lit.String(""), Lit.String("mutated!"))
      }
    }

  }

  object ScalaOptions extends MutatorGroup {
    override val groupName: String = "ScalaOptions"

    override val getSubMutators: List[Mutator] =
      List(
        GetOrElse,
        Exists,
        Forall,
        IsEmpty,
        NonEmpty,
        Fold,
        OrElse,
        OrNull,
        Filter,
        FilterNot,
        Contains
      )

    object GetOrElse extends SimpleMutator("GetOrElse") {
      override def getMutator(implicit doc: SemanticDocument): MutationResult = {
        case getOrElse @ Term.Apply(Term.Select(termName, Term.Name("getOrElse")), List(arg))
            if SymbolMatcher.exact("scala/Option#getOrElse().").matches(getOrElse.symbol) =>
          default(Term.Select(termName, Term.Name("get")), arg)
      }
    }

    object Exists extends SimpleMutator("Exists") {
      override def getMutator(implicit doc: SemanticDocument): MutationResult = {
        case exists @ Term.Apply(Term.Select(termName, Term.Name("exists")), args)
            if SymbolMatcher.exact("scala/Option#exists().").matches(exists.symbol) =>
          default(Term.Apply(Term.Select(termName, Term.Name("forall")), args))
      }
    }

    object Forall extends SimpleMutator("Forall") {
      override def getMutator(implicit doc: SemanticDocument): MutationResult = {
        case forall @ Term.Apply(Term.Select(termName, Term.Name("forall")), args)
            if SymbolMatcher.exact("scala/Option#forall().").matches(forall.symbol) =>
          default(Term.Apply(Term.Select(termName, Term.Name("exists")), args))
      }
    }

    object IsEmpty extends SimpleMutator("IsEmpty") {
      override def getMutator(implicit doc: SemanticDocument): MutationResult = {
        case isEmpty @ Term.Select(termName, Term.Name("isEmpty"))
            if SymbolMatcher.exact("scala/Option#isEmpty().").matches(isEmpty.symbol) =>
          default(Term.Select(termName, Term.Name("nonEmpty")))
      }
    }

    object NonEmpty extends SimpleMutator("NonEmpty") {
      override def getMutator(implicit doc: SemanticDocument): MutationResult = {
        case nonEmpty @ Term.Select(termName, Term.Name("nonEmpty" | "isDefined"))
            if SymbolMatcher
              .exact("scala/Option#nonEmpty().", "scala/Option#isDefined().")
              .matches(nonEmpty.symbol) =>
          default(Term.Select(termName, Term.Name("isEmpty")))
      }
    }

    object Fold extends SimpleMutator("Fold") {
      override def getMutator(implicit doc: SemanticDocument): MutationResult = {
        case fold @ Term.Apply(Term.Apply(Term.Select(_, Term.Name("fold")), List(argDefault)), _)
            if SymbolMatcher.exact("scala/Option#fold().").matches(fold.symbol) =>
          default(argDefault)
      }
    }

    object OrElse extends SimpleMutator("OrElse") {
      override def getMutator(implicit doc: SemanticDocument): MutationResult = {
        case orElse @ Term.Apply(Term.Select(termName, Term.Name("orElse")), List(arg))
            if SymbolMatcher.exact("scala/Option#orElse().").matches(orElse.symbol) =>
          default(termName, arg)
      }
    }

    object OrNull extends SimpleMutator("OrNull") {
      override def getMutator(implicit doc: SemanticDocument): MutationResult = {
        case orNull @ Term.Select(_, Term.Name("orNull"))
            if SymbolMatcher.exact("scala/Option#orNull().").matches(orNull.symbol) =>
          default(Lit.Null())
      }
    }

    object Filter extends SimpleMutator("Filter") {
      override def getMutator(implicit doc: SemanticDocument): MutationResult = {
        case filter @ Term.Apply(Term.Select(termName, Term.Name("filter")), args)
            if SymbolMatcher.exact("scala/Option#filter().").matches(filter.symbol) =>
          default(termName, Term.Apply(Term.Select(termName, Term.Name("filterNot")), args))
      }
    }

    object FilterNot extends SimpleMutator("FilterNot") {
      override def getMutator(implicit doc: SemanticDocument): MutationResult = {
        case filterNot @ Term.Apply(Term.Select(termName, Term.Name("filterNot")), args)
            if SymbolMatcher.exact("scala/Option#filterNot().").matches(filterNot.symbol) =>
          default(termName, Term.Apply(Term.Select(termName, Term.Name("filter")), args))
      }
    }

    object Contains extends SimpleMutator("Contains") {
      override def getMutator(implicit doc: SemanticDocument): MutationResult = {
        case contains @ Term.Apply(Term.Select(_, Term.Name("contains")), _)
            if SymbolMatcher.exact("scala/Option#contains().").matches(contains.symbol) =>
          default(Lit.Boolean(true), Lit.Boolean(false))
      }
    }
  }

  object ScalaTry extends MutatorGroup {
    override val groupName: String = "ScalaTry"

    override val getSubMutators: List[Mutator] =
      List(
        GetOrElse,
        OrElse
      )

    object GetOrElse extends SimpleMutator("GetOrElse") {
      override def getMutator(implicit doc: SemanticDocument): MutationResult = {
        case getOrElse @ Term.Apply(Term.Select(termName, Term.Name("getOrElse")), List(arg))
            if SymbolMatcher.exact("scala/util/Try#getOrElse().").matches(getOrElse.symbol) =>
          default(Term.Select(termName, Term.Name("get")), arg)
      }
    }

    object OrElse extends SimpleMutator("OrElse") {
      override def getMutator(implicit doc: SemanticDocument): MutationResult = {
        case orElse @ Term.Apply(Term.Select(termName, Term.Name("orElse")), List(arg))
            if SymbolMatcher.exact("scala/util/Try#orElse().").matches(orElse.symbol) =>
          default(termName, arg)
      }
    }
  }

  object Collections extends MutatorGroup {
    override val groupName: String = "Collections"

    private val MaxSize = 25

    @tailrec
    private def removeOneArg(
        before: List[Term],
        terms: List[Term],
        result: List[List[Term]]
    ): List[List[Term]] =
      terms match {
        case Nil =>
          result
        case term :: others =>
          removeOneArg(before :+ term, others, before ++ others :: result)
      }

    class RemoveApplyArgMutator(
        mutatorName: String,
        collectionName: String,
        val symbolsToMatch: Seq[String],
        minimum: Int
    ) extends SimpleMutator(mutatorName) {
      override def getMutator(implicit doc: SemanticDocument): MutationResult = {
        case collection @ Term.Apply(
              select @ (Term.Name(`collectionName`) | Term.Select(_, Term.Name(`collectionName`))),
              args
            )
            if args.lengthCompare(minimum) >= 0 && args.lengthCompare(MaxSize) <= 0 &&
              SymbolMatcher.exact(symbolsToMatch: _*).matches(collection.symbol) =>
          default(removeOneArg(Nil, args, Nil).reverse.map(Term.Apply(select, _)): _*)
      }
    }

    val ListApply: RemoveApplyArgMutator =
      new RemoveApplyArgMutator(
        "ListApply",
        "List",
        Seq(
          "scala/collection/immutable/List.",
          "scala/package.List."
        ),
        minimum = 1
      )

    val SeqApply: RemoveApplyArgMutator =
      new RemoveApplyArgMutator(
        "SeqApply",
        "Seq",
        Seq(
          "scala/collection/Seq.",
          "scala/collection/mutable/Seq.",
          "scala/collection/immutable/Seq.",
          "scala/package.Seq."
        ),
        minimum = 1
      )

    val SetApply: RemoveApplyArgMutator =
      new RemoveApplyArgMutator(
        "SetApply",
        "Set",
        Seq(
          "scala/Predef.Set.",
          "scala/collection/mutable/Set.",
          "scala/collection/immutable/Set.",
          "scala/package.Set."
        ),
        minimum = 2
      )

    val ReverseSymbols: Seq[String] =
      Seq(
        "scala/collection/SeqLike#reverse().",
        "scala/collection/immutable/List#reverse().",
        "scala/collection/IndexedSeqOptimized#reverse().",
        "scala/collection/SeqOps#reverse().",
        "scala/collection/IndexedSeqOps#reverse().",
        "scala/collection/ArrayOps#reverse().",
        "scala/collection/StringOps#reverse()."
      )

    val Reverse: SimpleMutator =
      new SimpleMutator("Reverse") {
        override def getMutator(implicit doc: SemanticDocument): MutationResult = {
          case reverse @ Term.Select(term, Term.Name("reverse"))
              if SymbolMatcher.exact(ReverseSymbols: _*).matches(reverse.symbol) =>
            default(term)
        }
      }

    override val getSubMutators: List[Mutator] =
      List(
        ListApply,
        SeqApply,
        SetApply,
        Reverse
      )

  }

  object PartialFunctions extends MutatorGroup {
    override val groupName: String = "PartialFunctions"

    override val getSubMutators: List[Mutator] =
      List(
        RemoveOneCase,
        RemoveOneAlternative
      )

    object RemoveOneCase extends SimpleMutator("RemoveOneCase") {
      override def getMutator(implicit doc: SemanticDocument): MutationResult = {
        case Term.PartialFunction(cases) if cases.lengthCompare(2) >= 0 =>
          @tailrec
          def removeOneCase(
              before: List[Case],
              terms: List[Case],
              result: List[List[Case]]
          ): List[List[Case]] =
            terms match {
              case Nil =>
                result
              case caseTerm :: others =>
                removeOneCase(before :+ caseTerm, others, (before ++ others) :: result)
            }

          NeedsParens(removeOneCase(Nil, cases, Nil).reverse.map(Term.PartialFunction(_)))
      }
    }

    object RemoveOneAlternative extends SimpleMutator("RemoveOneAlternative") {
      override def getMutator(implicit doc: SemanticDocument): MutationResult = {
        case Term.PartialFunction(cases) =>
          def findAlternatives(mainPat: Pat): List[Pat] =
            mainPat match {
              case Pat.Bind(name, pat) =>
                findAlternatives(pat).map(Pat.Bind(name, _))
              case Pat.Extract(term, pats) =>
                pats.zipWithIndex
                  .flatMap { case (pat, index) => findAlternatives(pat).map((_, index)) }
                  .map { case (mutated, index) => Pat.Extract(term, pats.updated(index, mutated)) }
              case Pat.Alternative(pat1, pat2) =>
                findAlternatives(pat1) ++ findAlternatives(pat2)
              case pat =>
                List(pat)
            }

          @tailrec
          def changeOneCase(
              before: List[Case],
              terms: List[Case],
              result: List[List[Case]]
          ): List[List[Case]] =
            terms match {
              case Nil =>
                result
              case caseTerm :: others =>
                val alternatives =
                  findAlternatives(caseTerm.pat)
                    .filterNot(_.structure == caseTerm.pat.structure)

                val caseTermsMutated =
                  if (alternatives.length > 1)
                    alternatives
                      .map(pat => caseTerm.copy(pat = pat))
                      .reverse
                  else
                    Nil

                changeOneCase(
                  before :+ caseTerm,
                  others,
                  caseTermsMutated.map(term => (before :+ term) ++ others) ++ result
                )
            }

          NeedsParens(changeOneCase(Nil, cases, Nil).reverse.map(Term.PartialFunction(_)))
      }
    }
  }

  object ScalaStrings extends MutatorGroup {
    override val groupName: String = "ScalaStrings"

    override val getSubMutators: List[Mutator] =
      List(
        Concat,
        Trim,
        ToUpperCase,
        ToLowerCase
      )

    object Concat extends SimpleMutator("Concat") {
      override def getMutator(implicit doc: SemanticDocument): MutationResult = {
        case concat @ Term.ApplyInfix(_, Term.Name("+"), _, _)
            if SymbolMatcher.exact("java/lang/String#`+`().").matches(concat.symbol) =>
          fullReplace(Lit.String("mutated!"), Lit.String(""))
        case concat @ Term.Apply(Term.Select(_, Term.Name("concat")), _)
            if SymbolMatcher.exact("java/lang/String#concat().").matches(concat.symbol) =>
          fullReplace(Lit.String("mutated!"), Lit.String(""))
      }
    }

    object Trim extends SimpleMutator("Trim") {
      override def getMutator(implicit doc: SemanticDocument): MutationResult = {
        case trim @ Term.Select(term @ _, Term.Name("trim"))
            if SymbolMatcher.exact("java/lang/String#trim().").matches(trim.symbol) =>
          default(term)
      }
    }

    object ToUpperCase extends SimpleMutator("ToUpperCase") {
      override def getMutator(implicit doc: SemanticDocument): MutationResult = {
        case toUpperCase @ Term.Select(term @ _, Term.Name("toUpperCase"))
            if SymbolMatcher
              .exact("java/lang/String#toUpperCase(+1).")
              .matches(toUpperCase.symbol) =>
          default(term)
        case toUpperCase @ Term.Apply(Term.Select(term @ _, Term.Name("toUpperCase")), _)
            if SymbolMatcher
              .exact("java/lang/String#toUpperCase().")
              .matches(toUpperCase.symbol) =>
          default(term)
      }
    }

    object ToLowerCase extends SimpleMutator("ToLowerCase") {
      override def getMutator(implicit doc: SemanticDocument): MutationResult = {
        case toLowerCase @ Term.Select(term @ _, Term.Name("toLowerCase"))
            if SymbolMatcher
              .exact("java/lang/String#toLowerCase(+1).")
              .matches(toLowerCase.symbol) =>
          default(term)
        case toLowerCase @ Term.Apply(Term.Select(term @ _, Term.Name("toLowerCase")), _)
            if SymbolMatcher
              .exact("java/lang/String#toLowerCase().")
              .matches(toLowerCase.symbol) =>
          default(term)
      }
    }

  }

  private def default(terms: Term*): ReplaceType = Standard(terms.toList)

  private def fullReplace(terms: Term*): ReplaceType = FullReplace(terms.toList)
}
