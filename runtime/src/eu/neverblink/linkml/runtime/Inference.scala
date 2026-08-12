package eu.neverblink.linkml.runtime

import eu.neverblink.linkml.runtime.FastUtils.*

/** Thrown when a slot's value contradicts the value inferred from its `equals_expression`, or when
  * an expression references a slot that has no value.
  */
final class InferenceException(message: String) extends RuntimeException(message)

/** Fill in an optional slot from its `equals_expression`, or check that the value already there
  * agrees with the inferred one.
  *
  * @param slotName
  *   Name of the slot being inferred, for error messages
  * @param current
  *   The slot's current value
  * @param inferred
  *   The value computed from the slot's `equals_expression`
  * @return
  *   The inferred value if the slot was empty, otherwise its current value
  */
def inferOptional[T](slotName: String, current: Option[T], inferred: T): Option[T] =
  current.foldFast(new Some(inferred)) { value =>
    if (value == inferred) current
    else {
      throw InferenceException(
        s"Slot '$slotName' is set to '$value', but its expression infers '$inferred'",
      )
    }
  }

/** Check that a required slot's value agrees with its `equals_expression`. A required slot is never
  * empty, so there is nothing to fill in.
  *
  * @param slotName
  *   Name of the slot being checked, for error messages
  * @param current
  *   The slot's current value
  * @param inferred
  *   The value computed from the slot's `equals_expression`
  * @return
  *   The slot's current value
  */
def inferRequired[T](slotName: String, current: T, inferred: T): T =
  if current == inferred then current
  else
    throw InferenceException(
      s"Slot '$slotName' is set to '$current', but its expression infers '$inferred'",
    )

/** Read the value of a slot referenced by an `equals_expression`. The referenced slot must have a
  * value, as there is no sensible way to interpolate an absent one.
  *
  * @param slotName
  *   Name of the referenced slot, for error messages
  * @param value
  *   The referenced slot's current value
  */
def inferenceInput[T](slotName: String, value: Option[T]): T =
  value.getOrElseFast(
    throw InferenceException(
      s"Slot '$slotName' is referenced by an expression, but has no value",
    ),
  )
