package com.groupironmencompanion.place;

import java.util.Objects;

/**
 * A named place and how a member stands in relation to it: at it, near it, or beneath it.
 * <p>
 * Kept apart from the words around it so an activity can choose its own preposition:
 * "Fishing at Catherby", "Idle in Catherby", but always "Mining beneath Falador" and
 * "Fishing near Port Sarim" whatever the activity would have said.
 */
public final class Place
{
	public enum Relation
	{
		AT,
		NEAR,
		BENEATH
	}

	private final String name;
	private final Relation relation;
	private final boolean upstairs;

	public Place(String name, Relation relation, boolean upstairs)
	{
		this.name = Objects.requireNonNull(name);
		this.relation = Objects.requireNonNull(relation);
		this.upstairs = upstairs;
	}

	public static Place at(String name)
	{
		return new Place(name, Relation.AT, false);
	}

	public String getName()
	{
		return name;
	}

	public Relation getRelation()
	{
		return relation;
	}

	public boolean isUpstairs()
	{
		return upstairs;
	}

	/**
	 * The place as the end of a sentence: {@code phrase("at")} gives "at Catherby", while a
	 * place that is only near or beneath somewhere says so instead of the preposition given.
	 */
	public String phrase(String preposition)
	{
		String lead;
		switch (relation)
		{
			case NEAR:
				lead = "near";
				break;
			case BENEATH:
				lead = "beneath";
				break;
			default:
				lead = preposition;
				break;
		}
		return lead + " " + name + (upstairs ? ", upstairs" : "");
	}

	/** The place on its own: "Catherby", "Near Catherby", "Beneath Falador". */
	@Override
	public String toString()
	{
		switch (relation)
		{
			case NEAR:
				return "Near " + name + (upstairs ? ", upstairs" : "");
			case BENEATH:
				return "Beneath " + name;
			default:
				// A few names read as part of a sentence ("their house"), so start it properly.
				String capitalised = name.isEmpty() ? name
					: Character.toUpperCase(name.charAt(0)) + name.substring(1);
				return capitalised + (upstairs ? ", upstairs" : "");
		}
	}

	@Override
	public boolean equals(Object o)
	{
		if (this == o)
		{
			return true;
		}
		if (!(o instanceof Place))
		{
			return false;
		}
		Place other = (Place) o;
		return upstairs == other.upstairs && relation == other.relation && name.equals(other.name);
	}

	@Override
	public int hashCode()
	{
		return Objects.hash(name, relation, upstairs);
	}
}
